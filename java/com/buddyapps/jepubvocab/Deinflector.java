package com.buddyapps.jepubvocab;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Japanese deinflection. The rule table is generated from hoshidicts
 * (Manhhao/hoshidicts), which follows Yomitan's Japanese transforms.
 */
final class Deinflector {

    static final class Result {
        final String text;
        final int conditions;
        final List<String> trace;

        Result(String text, int conditions, List<String> trace) {
            this.text = text;
            this.conditions = conditions;
            this.trace = trace;
        }
    }

    private static final Map<String, List<DeinflectorRules.Rule>> TRANSFORMS;
    private static final int MAX_LENGTH;

    static {
        TRANSFORMS = new HashMap<String, List<DeinflectorRules.Rule>>();
        int max = 0;
        for (DeinflectorRules.Rule rule : DeinflectorRules.RULES) {
            List<DeinflectorRules.Rule> list = TRANSFORMS.get(rule.from);
            if (list == null) {
                list = new ArrayList<DeinflectorRules.Rule>();
                TRANSFORMS.put(rule.from, list);
            }
            list.add(rule);
            max = Math.max(max, rule.from.codePointCount(0, rule.from.length()));
        }
        MAX_LENGTH = max;
    }

    interface StepVisitor {
        void visit(String text, int conditions, int steps);
    }

    private Deinflector() { }

    static List<Result> deinflect(String text) {
        ArrayList<Result> results = new ArrayList<Result>();
        ArrayList<String> trace = new ArrayList<String>();
        if (text.codePointCount(0, text.length()) > 1) {
            recurse(text, DeinflectorRules.NONE, trace, results);
        } else {
            results.add(new Result(text, DeinflectorRules.NONE, trace));
        }
        return results;
    }

    /** Lightweight deinflection that skips trace list allocation for tokenization. */
    static void deinflectSteps(String text, StepVisitor visitor) {
        if (text == null || text.length() <= 1) return;
        if (!hasSurrogate(text)) {
            recurseBmp(text, DeinflectorRules.NONE, 0, visitor);
        } else if (text.codePointCount(0, text.length()) > 1) {
            recurseSteps(text, DeinflectorRules.NONE, 0, visitor);
        }
    }

    private static boolean hasSurrogate(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (Character.isSurrogate(s.charAt(i))) return true;
        }
        return false;
    }

    private static void recurseBmp(String text, int conditions, int steps,
                                   StepVisitor visitor) {
        int len = text.length();
        if (len <= 1 || steps > 6) return;
        if (conditions != DeinflectorRules.NONE) {
            visitor.visit(text, conditions, steps);
        }
        int start = Math.min(MAX_LENGTH, len);
        for (int i = start; i > 0; i--) {
            int from = len - i;
            String suffix = text.substring(from);
            List<DeinflectorRules.Rule> rules = TRANSFORMS.get(suffix);
            if (rules != null) {
                String prefix = text.substring(0, from);
                for (DeinflectorRules.Rule rule : rules) {
                    if (conditions != DeinflectorRules.NONE
                            && (conditions & rule.conditionsIn) == 0) continue;
                    recurseBmp(prefix + rule.to, rule.conditionsOut, steps + 1, visitor);
                }
            }
        }
    }

    private static void recurseSteps(String text, int conditions, int steps,
                                     StepVisitor visitor) {
        int[] cps = text.codePoints().toArray();
        if (cps.length <= 1 || steps > 6) return;
        if (conditions != DeinflectorRules.NONE) {
            visitor.visit(text, conditions, steps);
        }
        int start = Math.min(MAX_LENGTH, cps.length);
        for (int i = start; i > 0; i--) {
            int from = cps.length - i;
            String suffix = new String(cps, from, i);
            List<DeinflectorRules.Rule> rules = TRANSFORMS.get(suffix);
            if (rules != null) {
                String prefix = new String(cps, 0, from);
                for (DeinflectorRules.Rule rule : rules) {
                    if (conditions != DeinflectorRules.NONE
                            && (conditions & rule.conditionsIn) == 0) continue;
                    recurseSteps(prefix + rule.to, rule.conditionsOut, steps + 1, visitor);
                }
            }
        }
    }

    private static void recurse(String text, int conditions, List<String> trace,
                                List<Result> results) {
        int[] cps = text.codePoints().toArray();
        if (cps.length <= 1) return;
        results.add(new Result(text, conditions, new ArrayList<String>(trace)));

        int start = Math.min(MAX_LENGTH, cps.length);
        for (int i = start; i > 0; i--) {
            int from = cps.length - i;
            String suffix = new String(cps, from, i);
            List<DeinflectorRules.Rule> rules = TRANSFORMS.get(suffix);
            if (rules != null) {
                String prefix = new String(cps, 0, from);
                for (DeinflectorRules.Rule rule : rules) {
                    if (conditions != DeinflectorRules.NONE
                            && (conditions & rule.conditionsIn) == 0) continue;
                    trace.add(DeinflectorRules.GROUPS[rule.group]);
                    recurse(prefix + rule.to, rule.conditionsOut, trace, results);
                    trace.remove(trace.size() - 1);
                }
            }
        }
    }
}
