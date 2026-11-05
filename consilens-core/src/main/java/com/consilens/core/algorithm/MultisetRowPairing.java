package com.consilens.core.algorithm;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 同键多行的多重集配对（重复键开放档 validateUniqueKeys=false 的语义实现）。
 *
 * <p>规则：
 * <ol>
 *   <li><b>值相等的行互相抵消</b>——数据相同必须报 0，重复本身不是差异；</li>
 *   <li>抵消后的剩余行在两侧各按"规范化整行签名"排序，<b>按序 1:1 配对</b>，
 *       逐对作为 MISMATCH（含 changedColumns）；</li>
 *   <li>配不上的行按所在侧报 SM/TM；</li>
 *   <li>每行带 {@code occurrence}（键分组内按签名排序后的 1-based 序号），
 *       键带 {@code keyMultiplicity}（两侧行数），让"键不唯一"可见。</li>
 * </ol>
 *
 * <p>checksum 的归因（LocalDiffEngine）与 streaming 的分组比较（ConnectorRecordDiffer）
 * 共用本类，保证两条计划对重复键给出完全一致的结论。
 */
public final class MultisetRowPairing {

    private MultisetRowPairing() {
    }

    /** 配对结果：按各自输入列表的下标给出。 */
    public static final class Outcome {
        /** 值相等的行对（互相抵消，不产出差异）。 */
        public final List<int[]> equalPairs = new ArrayList<>();
        /** 剩余行按序配对（值不同），逐对产出 MISMATCH。 */
        public final List<int[]> mismatchPairs = new ArrayList<>();
        /** 仅 source 侧多出的行。 */
        public final List<Integer> sourceOnly = new ArrayList<>();
        /** 仅 target 侧多出的行。 */
        public final List<Integer> targetOnly = new ArrayList<>();
        /** 每行在键分组内按签名排序后的 1-based 序号（下标与输入列表对齐）。 */
        public final int[] sourceOccurrence;
        public final int[] targetOccurrence;

        Outcome(int[] sourceOccurrence, int[] targetOccurrence) {
            this.sourceOccurrence = sourceOccurrence;
            this.targetOccurrence = targetOccurrence;
        }
    }

    public static Outcome pair(List<String> sourceSignatures, List<String> targetSignatures) {
        int n = sourceSignatures.size();
        int m = targetSignatures.size();
        List<Integer> sourceOrder = sortedIndices(sourceSignatures);
        List<Integer> targetOrder = sortedIndices(targetSignatures);
        int[] sourceOccurrence = new int[n];
        int[] targetOccurrence = new int[m];
        for (int k = 0; k < n; k++) {
            sourceOccurrence[sourceOrder.get(k)] = k + 1;
        }
        for (int k = 0; k < m; k++) {
            targetOccurrence[targetOrder.get(k)] = k + 1;
        }
        Outcome outcome = new Outcome(sourceOccurrence, targetOccurrence);

        int i = 0;
        int j = 0;
        List<Integer> sourceRest = new ArrayList<>();
        List<Integer> targetRest = new ArrayList<>();
        while (i < n && j < m) {
            int comparison = sourceSignatures.get(sourceOrder.get(i))
                    .compareTo(targetSignatures.get(targetOrder.get(j)));
            if (comparison == 0) {
                outcome.equalPairs.add(new int[] { sourceOrder.get(i), targetOrder.get(j) });
                i++;
                j++;
            } else if (comparison < 0) {
                sourceRest.add(sourceOrder.get(i++));
            } else {
                targetRest.add(targetOrder.get(j++));
            }
        }
        while (i < n) {
            sourceRest.add(sourceOrder.get(i++));
        }
        while (j < m) {
            targetRest.add(targetOrder.get(j++));
        }

        int pairs = Math.min(sourceRest.size(), targetRest.size());
        for (int k = 0; k < pairs; k++) {
            outcome.mismatchPairs.add(new int[] { sourceRest.get(k), targetRest.get(k) });
        }
        for (int k = pairs; k < sourceRest.size(); k++) {
            outcome.sourceOnly.add(sourceRest.get(k));
        }
        for (int k = pairs; k < targetRest.size(); k++) {
            outcome.targetOnly.add(targetRest.get(k));
        }
        return outcome;
    }

    /**
     * 整行签名：每个规范化值用 {@code 长度:内容} 编码后串联。
     * 不能用固定分隔符直接拼接——值本身含分隔符时不同行会得到相同签名，
     * 重复键配对会把不相等的行误判为“相等抵消”。
     */
    public static String signature(List<?> normalizedValues) {
        StringBuilder builder = new StringBuilder();
        for (Object value : normalizedValues) {
            String text = value == null ? "" : String.valueOf(value);
            builder.append(text.length()).append(':').append(text);
        }
        return builder.toString();
    }

    /** 重复键场景附加到差异行上的元数据。 */
    public static Map<String, Object> metadata(int occurrence, int sourceRows, int targetRows) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("occurrence", occurrence);
        metadata.put("keyMultiplicity", "source=" + sourceRows + ", target=" + targetRows);
        return metadata;
    }

    private static List<Integer> sortedIndices(List<String> signatures) {
        List<Integer> indices = new ArrayList<>(signatures.size());
        for (int k = 0; k < signatures.size(); k++) {
            indices.add(k);
        }
        indices.sort(Comparator.comparing(signatures::get));
        return indices;
    }
}
