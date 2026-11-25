package com.buddyapps.jepubvocab;

import android.content.Context;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

/**
 * Offline lookup over a compact subset of JMdict (common entries, three senses
 * each). Surface forms are indexed in memory; inflected forms are handled by
 * the Yomitan-derived deinflector.
 */
final class JMDict {

    static final class Entry {
        final String word;
        final String reading;
        final List<String> partsOfSpeech;
        final List<String> glosses;
        final int conditions;

        Entry(String word, String reading, String[] pos, String[] glosses) {
            this.word = word;
            this.reading = reading;
            this.partsOfSpeech = Arrays.asList(pos);
            this.glosses = Arrays.asList(glosses);
            this.conditions = conditionsFor(pos);
        }
    }

    static final class Hit {
        final Entry entry;
        final String matched;
        final List<String> trace;

        Hit(Entry entry, String matched, List<String> trace) {
            this.entry = entry;
            this.matched = matched;
            this.trace = trace;
        }
    }

    private static Map<String, Entry> byForm;

    private JMDict() { }

    static synchronized void load(Context c) {
        if (byForm != null) return;
        HashMap<String, Entry> map = new HashMap<String, Entry>();
        try {
            InputStream in = c.getAssets().open("jmdict.txt");
            BufferedReader reader = new BufferedReader(new InputStreamReader(in, "UTF-8"));
            String line;
            while ((line = reader.readLine()) != null) {
                String[] fields = line.split("\t", -1);
                if (fields.length < 4) continue;
                String[] forms = fields[0].split(",");
                String[] pos = fields[2].isEmpty() ? new String[0] : fields[2].split(",");
                String[] glosses = fields[3].split("\u001f", -1);
                Entry entry = new Entry(forms[0], fields[1], pos, glosses);
                for (String form : forms) {
                    if (!form.isEmpty() && !map.containsKey(form)) map.put(form, entry);
                }
            }
            reader.close();
        } catch (IOException e) {
            map.clear();
        }
        byForm = map;
    }

    static List<Hit> lookup(String text) {
        if (byForm == null || text == null || text.isEmpty())
            return new ArrayList<Hit>();
        List<Hit> hits = exact(text);
        if (hits.isEmpty() && text.codePointCount(0, text.length()) > 2) {
            int[] cps = text.codePoints().toArray();
            int start = Math.min(cps.length - 1, 12);
            for (int len = start; len >= 2 && hits.isEmpty(); len--) {
                String candidate = new String(cps, 0, len);
                hits = exact(candidate);
            }
        }
        return hits;
    }

    private static List<Hit> exact(String text) {
        ArrayList<Hit> hits = new ArrayList<Hit>();
        HashSet<String> seen = new HashSet<String>();
        for (Deinflector.Result r : Deinflector.deinflect(text)) {
            Entry entry = byForm.get(r.text);
            if (entry == null) continue;
            if (r.conditions != DeinflectorRules.NONE
                    && (entry.conditions & r.conditions) == 0) continue;
            if (!seen.add(entry.word + "\u0000" + r.text)) continue;
            hits.add(new Hit(entry, r.text, r.trace));
        }
        return hits;
    }

    private static int conditionsFor(String[] pos) {
        int c = 0;
        for (String p : pos) {
            if (p.startsWith("Ichidan verb - zuru verb")) c |= DeinflectorRules.VZ;
            else if (p.startsWith("Ichidan verb")) c |= DeinflectorRules.V1;
            else if (p.startsWith("Godan verb")) c |= DeinflectorRules.V5;
            else if (p.startsWith("Kuru verb")) c |= DeinflectorRules.VK;
            else if (p.startsWith("suru verb") || p.startsWith("su verb")
                    || p.contains("aux. verb suru") || p.equals("copula")) c |= DeinflectorRules.VS;
            else if (p.startsWith("adjective (keiyoushi)")) c |= DeinflectorRules.ADJ_I;
            else if (p.contains("verb")) c |= DeinflectorRules.V;
        }
        return c;
    }
}
