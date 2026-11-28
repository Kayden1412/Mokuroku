# Mokuroku

An Android app for Japanese EPUBs. Pick many `.epub` files, and it parses them into:

- a library with card, list or grid display, covers, search, and sorting by name,
  time added, characters, words, dialogue %, or average sentence length,
- a page-by-page reader,
- a vocabulary list extracted from the Japanese text, with word search and a
  sort dropdown for book frequency, Jiten frequency, first/last appearance or
  word order, each in both directions, with offline definitions from an embedded
  subset of JMdict and Yomitan-style deinflection so inflected forms resolve to
  their dictionary entry,
- a "books with similar content" list on each book **and** on each page,
  based on rank-weighted shared vocabulary and character patterns, with a
  minimum-match filter on each book page.

Tapping a vocabulary word copies it, looks it up offline, or opens Jisho.

## Credits
Icons from [Lucide](https://lucide.dev/) (ISC License).

Definitions are from [JMdict](https://www.edrdg.org/jmdict/edict_doc.html)
(EDRDG, CC BY-SA 3.0); the embedded file contains the common-word subset.
Person-name filtering uses a Bloom filter over kanji forms from
[JMnedict](https://www.edrdg.org/enamdict/edict_doc.html) (EDRDG, CC BY-SA 4.0),
regenerated with `scripts/gen_jmnedict_bloom.py`.
Deinflection rules are derived from
[hoshidicts](https://github.com/Manhhao/hoshidicts), which follows Yomitan's
Japanese transforms. Regenerate them with `scripts/gen_deinflector.py` from a
shallow clone. Matcher word ranks come from the full [Jiten global frequency dictionary](https://jiten.moe/frequency-dictionaries)
(CC BY-SA 4.0) — all ~463k unique words, not just the top 100k.

Both dictionaries are compiled into a single prebuilt binary trie at
`assets/dict.bin`. To regenerate it, run `scripts/build_jmdict.py` (from
`JMdict_e.gz`) and `scripts/gen_jiten_rank.py` (from Jiten's Global CSV) to
refresh the intermediate text files in `data/`, then run
`python3 scripts/gen_dict_bin.py`. The app reads the binary directly, so startup
does not parse text files or build a large HashMap.

## Build Locally

Requires the Android build toolchain (JDK 17, Android 36 platform, build-tools 36):

```sh
SDK_JAR=$ANDROID_HOME/platforms/android-36/android.jar \
PATH=$ANDROID_HOME/build-tools/36.0.0:$PATH \
bash scripts/build-apk.sh .
```

The signed APK appears at `build/app.apk`.

## How it works

- Picked EPUBs are staged in the cache only while parsing, then deleted. Only the
  extracted text, vocabulary/word profiles and a resized cover are stored, in an
  SQLite database (`mokuroku.db`), so launch never reparses the source books.
  EPUBs copied by older versions are shown in the **Storage** tab and can be
  deleted there.
- Text is stripped of markup, split into ~1400-character pages, and tokenized with
  a rank-scored, POS-aware beam tokenizer over a compact flat-array trie of
  JMdict and Jiten forms precompiled into `assets/dict.bin`. Script runs (kanji
  + okurigana, hiragana, katakana) fall back to morphological heuristics,
  stem-gated deinflection collapses inflections to lemmas, and colloquial
  contractions (`食べてる`, `行かなきゃ`) plus emphatic elongation (`すごーい`)
  are expanded before lookup. Ruby readings (`<rt>`) are kept as furigana hints
  so homographs and ruby bases resolve correctly. Full-width ASCII is folded to
  half-width and Japanese punctuation/quotes act as word boundaries. Common
  particles are filtered out.
- Similarity blends sublinear-TF (`1 + ln(count)`), rank-weighted TF-IDF
  cosine over deinflected content words (JMdict POS filtering + Jiten global
  ranks, with low-overlap damping) and sublinear-TF Japanese character bigrams.
  Common words are omitted from the shared-word list.
- The **Library** tab is the main screen; **Storage** shows how much space computed
  data and old EPUB copies use and lets you delete either per book or in bulk.
- Cover images are downscaled to 360×480 JPEGs to keep the app small.
