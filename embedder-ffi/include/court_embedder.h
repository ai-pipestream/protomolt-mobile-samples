#ifndef COURT_EMBEDDER_H
#define COURT_EMBEDDER_H

#include <stddef.h>
#include <stdint.h>
#include <sys/types.h>

#ifdef __cplusplus
extern "C" {
#endif

typedef struct CourtEmbedder CourtEmbedder;

// Loads a Model2Vec directory. Null on failure; see court_embedder_last_error.
CourtEmbedder *court_embedder_open(const char *dir);
// The length of every vector this model produces; 0 for a null handle.
size_t court_embedder_dim(const CourtEmbedder *embedder);
// Embeds UTF-8 text into out. Returns floats written; 0 when the text has no
// vector (empty, or entirely out of vocabulary); -1 on error.
ssize_t court_embedder_embed(const CourtEmbedder *embedder, const uint8_t *text, size_t text_len,
                             float *out, size_t out_len);
// WordPiece pieces the text tokenizes to, [UNK]s included; 0 on a null handle.
size_t court_embedder_pieces(const CourtEmbedder *embedder, const uint8_t *text, size_t text_len);
void court_embedder_close(CourtEmbedder *embedder);
// The last failure on this thread; valid until the next failing call on it.
const char *court_embedder_last_error(void);

#ifdef __cplusplus
}
#endif
#endif
