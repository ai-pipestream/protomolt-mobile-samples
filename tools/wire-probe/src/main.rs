//! Drives the mobile C ABI exactly as a phone app does: protobuf bytes in, one
//! encoded MobileResponse out. Proves plan -> mapped ingest -> flush -> lexical
//! and dense query -> close -> reopen against the court fixture on the host, so
//! the Swift and Kotlin apps have a known-good sequence to reproduce.

use prost::Message;
use prost_types::field_descriptor_proto::{Label, Type};
use prost_types::{DescriptorProto, FieldDescriptorProto, FileDescriptorProto, FileDescriptorSet};
use protomolt_search_embedded::analyzer::body_spec;
use protomolt_search_embedded::pb::mobile::{
    mobile_response, MobileCloseResponse, MobileFlushResponse, MobileIngestMappedBatch,
    MobileOpenRequest, MobileOpenResponse, MobileResponse, MobileShardConfig,
};
use protomolt_search_embedded::pb::{
    ingest_mapped_request, search_query, selection_query, DenseQuery, IngestMappedRequest,
    IngestMappedResponse, LexicalQuery, MappedBind, MappedFieldAnalysis, PlanIndexRequest, PlanIndexResponse,
    QueryRequest, QueryResponse, SearchQuery, SelectionQuery,
};
use protomolt_search_embedded::MobileBuffer;

extern "C" {
    fn protomolt_search_open(request: *const u8, len: usize, create: u8) -> MobileBuffer;
    fn protomolt_search_plan_index(handle: u64, request: *const u8, len: usize) -> MobileBuffer;
    fn protomolt_search_ingest_mapped(handle: u64, request: *const u8, len: usize) -> MobileBuffer;
    fn protomolt_search_query(handle: u64, request: *const u8, len: usize) -> MobileBuffer;
    fn protomolt_search_flush(handle: u64) -> MobileBuffer;
    fn protomolt_search_close(handle: u64) -> MobileBuffer;
    fn protomolt_search_buffer_free(buffer: MobileBuffer);
}

const MESSAGE_TYPE: &str = "court.v1.Opinion";

fn payload<T: Message + Default>(what: &str, buffer: MobileBuffer) -> T {
    let bytes = unsafe { std::slice::from_raw_parts(buffer.data, buffer.len) }.to_vec();
    unsafe { protomolt_search_buffer_free(buffer) };
    match MobileResponse::decode(bytes.as_slice()).expect("envelope").outcome {
        Some(mobile_response::Outcome::Payload(p)) => T::decode(p.as_slice()).expect("payload"),
        Some(mobile_response::Outcome::Error(e)) => panic!("{what}: code {} {}", e.code, e.message),
        None => panic!("{what}: empty envelope"),
    }
}

fn call<T: Message + Default>(
    what: &str,
    request: &impl Message,
    f: impl FnOnce(*const u8, usize) -> MobileBuffer,
) -> T {
    let bytes = request.encode_to_vec();
    payload(what, f(bytes.as_ptr(), bytes.len()))
}

fn field(name: &str, number: i32, typ: Type, label: Label) -> FieldDescriptorProto {
    FieldDescriptorProto {
        name: Some(name.into()),
        number: Some(number),
        label: Some(label as i32),
        r#type: Some(typ as i32),
        ..Default::default()
    }
}

fn descriptor_set() -> Vec<u8> {
    FileDescriptorSet {
        file: vec![FileDescriptorProto {
            name: Some("court.proto".into()),
            package: Some("court.v1".into()),
            message_type: vec![DescriptorProto {
                name: Some("Opinion".into()),
                field: vec![
                    field("id", 1, Type::String, Label::Optional),
                    field("title", 2, Type::String, Label::Optional),
                    field("body", 3, Type::String, Label::Optional),
                    field("embedding", 4, Type::Float, Label::Repeated),
                ],
                ..Default::default()
            }],
            syntax: Some("proto3".into()),
            ..Default::default()
        }],
    }
    .encode_to_vec()
}

fn string_field(out: &mut Vec<u8>, number: u32, value: &str) {
    prost::encoding::string::encode(number, &value.to_string(), out);
}

fn opinion(id: &str, title: &str, body: &str, embedding: &[f32]) -> Vec<u8> {
    let mut out = Vec::new();
    string_field(&mut out, 1, id);
    string_field(&mut out, 2, title);
    string_field(&mut out, 3, body);
    prost::encoding::float::encode_packed(4, &embedding.to_vec(), &mut out);
    out
}

fn query(selection: search_query::Query, id: &str) -> QueryRequest {
    QueryRequest {
        request_id: "wire-probe".into(),
        k: 5,
        selection_k: 5,
        selection: Some(SelectionQuery {
            node: Some(selection_query::Node::Search(SearchQuery {
                id: id.into(),
                query: Some(selection),
            })),
        }),
        ..Default::default()
    }
}

fn show(label: &str, response: &QueryResponse, titles: &[String]) {
    println!("{label}: {} hits", response.hits.len());
    for hit in &response.hits {
        let title = titles.get(hit.doc_id as usize).map(String::as_str).unwrap_or("?");
        println!("  doc_id={} score={:.4}  {title}", hit.doc_id, hit.score);
    }
}

fn main() {
    let fixture = std::env::args().nth(1).expect("usage: wire-probe <fixture.ndjson>");
    let rows: Vec<serde_json::Value> = std::fs::read_to_string(&fixture)
        .expect("fixture")
        .lines()
        .map(|l| serde_json::from_str(l).expect("row"))
        .collect();
    let vector = |row: &serde_json::Value| -> Vec<f32> {
        row["embedding"].as_array().unwrap().iter().map(|v| v.as_f64().unwrap() as f32).collect()
    };
    let titles: Vec<String> = rows.iter().map(|r| r["title"].as_str().unwrap().to_string()).collect();

    let root = std::env::temp_dir().join(format!("wire-probe-{}", std::process::id()));
    std::fs::create_dir_all(&root).unwrap();
    let open = MobileOpenRequest {
        shards: vec![MobileShardConfig {
            index_path: root.join("court.tv").to_string_lossy().into_owned(),
            // The plan lands every string field as a column; the shard must declare
            // each one or mapped ingest refuses with FAILED_PRECONDITION.
            facet_fields: vec!["id".into()],
            // Order matters: the FIRST bm25 field is what an unqualified LexicalQuery
            // searches. With "title" first, "habeas" finds nothing.
            bm25_fields: vec!["body".into(), "title".into()],
            ..Default::default()
        }],
        ..Default::default()
    };

    let opened: MobileOpenResponse =
        call("open", &open, |p, n| unsafe { protomolt_search_open(p, n, 1) });
    println!("open: handle={} shards={} no_egress={}", opened.handle, opened.shard_count, opened.no_egress);
    let handle = opened.handle;

    let planned: PlanIndexResponse = call(
        "plan_index",
        &PlanIndexRequest {
            descriptor_set: descriptor_set(),
            message_type: MESSAGE_TYPE.into(),
            ..Default::default()
        },
        |p, n| unsafe { protomolt_search_plan_index(handle, p, n) },
    );
    let plan = planned.plan.expect("plan");
    println!(
        "plan: fingerprint={} doc_id_path={:?} vector_path={:?}",
        plan.fingerprint, plan.doc_id_path, plan.vector_path
    );

    let mut requests = vec![IngestMappedRequest {
        payload: Some(ingest_mapped_request::Payload::Bind(MappedBind {
            descriptor_set: descriptor_set(),
            message_type: MESSAGE_TYPE.into(),
            expected_fingerprint: plan.fingerprint.clone(),
            body_path: "body".into(),
            // field_analysis names EVERY projected text path, body included, and
            // replaces the legacy `analysis` field (the two are mutually exclusive).
            // The native analyzer has no default: its output is persisted term identity.
            field_analysis: ["title", "body"]
                .into_iter()
                .map(|path| MappedFieldAnalysis { path: path.into(), analysis: Some(body_spec()) })
                .collect(),
            ..Default::default()
        })),
    }];
    for row in &rows {
        requests.push(IngestMappedRequest {
            payload: Some(ingest_mapped_request::Payload::Document(opinion(
                row["doc_id"].as_str().unwrap(),
                row["title"].as_str().unwrap(),
                row["body"].as_str().unwrap(),
                &vector(row),
            ))),
        });
    }
    let ingested: IngestMappedResponse = call(
        "ingest_mapped",
        &MobileIngestMappedBatch { shard: 0, requests },
        |p, n| unsafe { protomolt_search_ingest_mapped(handle, p, n) },
    );
    println!("ingest: added={} total={} first_id={}", ingested.added, ingested.total, ingested.first_id);

    let flushed: MobileFlushResponse = payload("flush", unsafe { protomolt_search_flush(handle) });
    println!("flush: {flushed:?}");

    let lexical = query(
        search_query::Query::Lexical(LexicalQuery {
            text: std::env::args().nth(2).unwrap_or_else(|| "habeas".into()),
            analysis: Some(body_spec()),
            ..Default::default()
        }),
        "lexical",
    );
    let dense = query(
        search_query::Query::Dense(DenseQuery { vector: vector(&rows[0]), ..Default::default() }),
        "dense",
    );
    let run = |handle: u64, label: &str| {
        let r: QueryResponse = call("lexical", &lexical, |p, n| unsafe { protomolt_search_query(handle, p, n) });
        show(&format!("{label} text \"habeas\""), &r, &titles);
        let r: QueryResponse = call("dense", &dense, |p, n| unsafe { protomolt_search_query(handle, p, n) });
        show(&format!("{label} knn of doc 0"), &r, &titles);
    };
    run(handle, "fresh");

    let closed: MobileCloseResponse = payload("close", unsafe { protomolt_search_close(handle) });
    println!("close: {}", closed.closed);
    let reopened: MobileOpenResponse =
        call("reopen", &open, |p, n| unsafe { protomolt_search_open(p, n, 0) });
    run(reopened.handle, "reopened");
    let _: MobileCloseResponse = payload("close", unsafe { protomolt_search_close(reopened.handle) });

    let bytes: u64 = walk(&root);
    println!("index on disk: {:.1} KiB", bytes as f64 / 1024.0);
    std::fs::remove_dir_all(root).ok();
}

fn walk(dir: &std::path::Path) -> u64 {
    std::fs::read_dir(dir).map(|entries| entries.flatten().map(|e| {
        let p = e.path();
        if p.is_dir() { walk(&p) } else { e.metadata().map(|m| m.len()).unwrap_or(0) }
    }).sum()).unwrap_or(0)
}
