# Mokuroku

An Android app for Japanese EPUBs. Pick many `.epub` files, and it parses them into:

- a library with card, list or grid display, covers, search, and sorting by name,
  time added, characters, words, dialogue %, or average sentence length,
- a page-by-page reader,
- a vocabulary list extracted from the Japanese text, with offline definitions
  from an embedded subset of JMdict and Yomitan-style deinflection so inflected
  forms resolve to their dictionary entry,
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
shallow clone, and regenerate the dictionary with `scripts/build_jmdict.py`.
Matcher word ranks come from [Jiten's global frequency dictionary](https://jiten.moe/frequency-dictionaries)
(CC BY-SA 4.0); regenerate the embedded top 100,000 entries with
`scripts/gen_jiten_rank.py` from its Global CSV.

## Build Locally

Requires the Android build toolchain (JDK 17, Android 36 platform, build-tools 36):

```sh
SDK_JAR=$ANDROID_HOME/platforms/android-36/android.jar \
PATH=$ANDROID_HOME/build-tools/36.0.0:$PATH \
bash scripts/build-apk.sh .
```

The signed APK appears at `build/app.apk`.

## How it works

- EPUBs are copied into the app's private storage and parsed as ZIP archives:
  `container.xml` → OPF metadata → spine-ordered XHTML files.
- Text is stripped of markup, split into ~1400-character pages, and tokenized with
  a dictionary-based longest-match tokenizer: the embedded JMdict surface forms
  are held in a compact trie, with script runs (kanji + okurigana, hiragana,
  katakana) as fallback. Full-width ASCII is folded to half-width and Japanese
  punctuation/quotes act as word boundaries. Common particles are filtered out.
- Similarity blends sublinear-TF (`1 + ln(count)`), rank-weighted TF-IDF
  cosine over deinflected content words (JMdict POS filtering + Jiten global
  ranks, with low-overlap damping) and sublinear-TF Japanese character bigrams.
  Common words are omitted from the shared-word list.
- Parsed books are cached so later launches start quickly. Use **Reload** after
  replacing files. Cover images are downscaled to 360×480 JPEGs to keep the app small.
