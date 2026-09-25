package dev.local.goblinsettlement.economy.math;

import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;

/**
 * 规格 v1：按库存快照计算材料缺口。
 *
 * <p>对 required 中的每种材料：
 * <pre>
 *   可用 = max(0, 库存 - 其他任务预留)
 *   缺口 = max(0, 需求 - 可用)
 * </pre>
 * 只输出正缺口，键按 String 自然升序迭代。
 *
 * <p>本类不做任何 I/O，不读世界/文件/网络/时间，无随机数与可变全局状态，不修改输入。
 */
public final class MaterialShortages {

    private MaterialShortages() {
        throw new AssertionError("no instances");
    }

    /**
     * 计算尚未满足的材料缺口。
     *
     * @param required             本任务尚未交付的需求量，非 null
     * @param accessibleStock      当前可领取的库存，非 null
     * @param reservedByOtherTasks 其他任务已预留的量（不含本任务自己的预留），非 null
     * @return 新建、不可修改、按键自然升序的缺口 Map；无缺口时为空 Map
     * @throws IllegalArgumentException 任一 Map、键或值为 null，键为空白，或值为负
     */
    public static Map<String, Long> calculate(
            Map<String, Long> required,
            Map<String, Long> accessibleStock,
            Map<String, Long> reservedByOtherTasks) {

        validate(required, "required");
        validate(accessibleStock, "accessibleStock");
        validate(reservedByOtherTasks, "reservedByOtherTasks");

        // TreeMap 提供 String 自然升序，同时是独立副本，输入随后被改动也不会影响结果。
        Map<String, Long> shortages = new TreeMap<>();

        for (Map.Entry<String, Long> entry : required.entrySet()) {
            String material = entry.getKey();
            long demand = entry.getValue();

            // 两个取值都落在 0..Long.MAX_VALUE，相减不会溢出。
            long stock = lookup(accessibleStock, material);
            long reserved = lookup(reservedByOtherTasks, material);
            long available = Math.max(0L, stock - reserved);

            long gap = Math.max(0L, demand - available);
            if (gap > 0L) {
                shortages.put(material, gap);
            }
        }

        return Collections.unmodifiableMap(shortages);
    }

    /** 缺失键按 0 处理。 */
    private static long lookup(Map<String, Long> source, String material) {
        Long value = source.get(material);
        return value == null ? 0L : value;
    }

    /** 完整校验：即使该 Map 未被 required 用到（如 required 为空）也要校验。 */
    private static void validate(Map<String, Long> source, String field) {
        if (source == null) {
            throw new IllegalArgumentException(field + " 不能为 null");
        }
        for (Map.Entry<String, Long> entry : source.entrySet()) {
            String key = entry.getKey();
            if (key == null) {
                throw new IllegalArgumentException(field + " 含有 null 键");
            }
            if (key.isBlank()) {
                throw new IllegalArgumentException(field + " 含有空白键：" + key);
            }
            Long value = entry.getValue();
            if (value == null) {
                throw new IllegalArgumentException(field + " 的键 '" + key + "' 值为 null");
            }
            if (value < 0L) {
                throw new IllegalArgumentException(
                        field + " 的键 '" + key + "' 值为负：" + value);
            }
        }
    }
}
