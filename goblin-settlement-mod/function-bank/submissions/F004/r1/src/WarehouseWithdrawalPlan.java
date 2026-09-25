package dev.local.goblinsettlement.economy.math;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * 规格 v1：跨公共仓库的取料方案。
 *
 * <p>在调用方给定的快照上做纯算术：把需求按材料名称自然升序逐个满足，每种材料先按仓库 ID
 * 自然升序、再按槽号升序取料，单槽取 {@code min(尚需, 槽中数量)}，不超取。
 * 只要有一种材料不足，就返回不完整方案且**不给部分取料清单**，避免调用方执行半截方案。
 *
 * <p>本类不做任何 I/O，不读世界/文件/网络/时间，无随机数与可变全局状态，不修改输入。
 * 结果只是稳定方案，不代表真实预留；真实库存可能随后变化。
 */
public final class WarehouseWithdrawalPlan {

    /** 待用槽位快照：某仓库某槽当前的实物数量。 */
    public record Slot(String warehouseId, int slotIndex, String material, long count) {}

    /** 一条取料指令。 */
    public record Withdrawal(String warehouseId, int slotIndex, String material, long count) {}

    /**
     * 取料方案。
     *
     * @param complete    true 表示所有材料均已足量，withdrawals 可执行
     * @param withdrawals 完整方案的取料清单；不完整时必为空
     * @param missing     每个正缺口，按材料名称自然升序
     */
    public record Plan(boolean complete, List<Withdrawal> withdrawals, Map<String, Long> missing) {}

    /** 槽位取用顺序：仓库 ID 自然升序，同一仓库内槽号升序。 */
    private static final Comparator<Slot> SLOT_ORDER = (left, right) -> {
        int byWarehouse = left.warehouseId().compareTo(right.warehouseId());
        if (byWarehouse != 0) {
            return byWarehouse;
        }
        return Integer.compare(left.slotIndex(), right.slotIndex());
    };

    /** 重复槽位判据：同一仓库的同一槽号只能出现一次，与材料名无关。 */
    private record SlotKey(String warehouseId, int slotIndex) {}

    private WarehouseWithdrawalPlan() {
        throw new AssertionError("no instances");
    }

    /**
     * 计算取料方案。
     *
     * @param required 本次尚需数量，非 null；缺失的材料视为不需要
     * @param slots    主程序已确认可访问的公共仓库槽位快照，非 null
     * @return 完整方案或带缺口的方案
     * @throws IllegalArgumentException 任一参数、元素、字段为 null；字符串为空白；
     *                                  数量或槽号为负；同一 (warehouseId, slotIndex) 重复
     */
    public static Plan calculate(Map<String, Long> required, List<Slot> slots) {
        requireRequired(required);
        requireSlots(slots);

        // TreeMap 保证材料名自然升序，同时是独立副本：输入随后改变不影响本结果。
        Map<String, Long> needs = new TreeMap<>(required);

        // 按材料分组，只保留数量大于零的槽；分组内部先排好序。
        Map<String, List<Slot>> usableByMaterial = new TreeMap<>();
        for (Slot slot : slots) {
            if (slot.count() > 0L) {
                List<Slot> group = usableByMaterial.get(slot.material());
                if (group == null) {
                    group = new ArrayList<>();
                    usableByMaterial.put(slot.material(), group);
                }
                group.add(slot);
            }
        }
        for (List<Slot> group : usableByMaterial.values()) {
            group.sort(SLOT_ORDER);
        }

        List<Withdrawal> withdrawals = new ArrayList<>();
        Map<String, Long> missing = new TreeMap<>();
        boolean complete = true;

        for (Map.Entry<String, Long> need : needs.entrySet()) {
            String material = need.getKey();
            long remaining = need.getValue();

            if (remaining == 0L) {
                continue;   // 零需求不生成取料行
            }

            List<Slot> group = usableByMaterial.get(material);
            if (group != null) {
                for (Slot slot : group) {
                    if (remaining == 0L) {
                        break;
                    }
                    // 不汇总全部槽数量，逐个相减，Long.MAX_VALUE 也不会溢出。
                    long taken = Math.min(remaining, slot.count());
                    withdrawals.add(new Withdrawal(slot.warehouseId(), slot.slotIndex(), material, taken));
                    remaining -= taken;     // taken <= remaining，不会下溢
                }
            }

            if (remaining > 0L) {
                complete = false;
                missing.put(material, remaining);
            }
        }

        if (!complete) {
            // 禁止调用方执行部分方案，因此不返回已算出的取料行。
            return new Plan(false, List.of(), java.util.Collections.unmodifiableMap(missing));
        }
        return new Plan(true, List.copyOf(withdrawals), java.util.Collections.unmodifiableMap(missing));
    }

    // ------------------------------------------------------------ 校验

    /** 完整校验需求表；required 为空也要走完（自身无条目时等价于只查 null）。 */
    private static void requireRequired(Map<String, Long> required) {
        if (required == null) {
            throw new IllegalArgumentException("required 不能为 null");
        }
        for (Map.Entry<String, Long> entry : required.entrySet()) {
            String material = entry.getKey();
            if (material == null) {
                throw new IllegalArgumentException("required 含有 null 材料名");
            }
            if (material.isBlank()) {
                throw new IllegalArgumentException("required 含有空白材料名：" + material);
            }
            Long amount = entry.getValue();
            if (amount == null) {
                throw new IllegalArgumentException("required 的材料 '" + material + "' 数量为 null");
            }
            if (amount < 0L) {
                throw new IllegalArgumentException("required 的材料 '" + material + "' 数量为负：" + amount);
            }
        }
    }

    /** 完整校验槽位表，含重复 (warehouseId, slotIndex) 检测；required 为空时同样执行。 */
    private static void requireSlots(List<Slot> slots) {
        if (slots == null) {
            throw new IllegalArgumentException("slots 不能为 null");
        }
        Set<SlotKey> seen = new HashSet<>();
        for (int index = 0; index < slots.size(); index++) {
            Slot slot = slots.get(index);
            if (slot == null) {
                throw new IllegalArgumentException("slots 第 " + index + " 个元素为 null");
            }

            String warehouseId = slot.warehouseId();
            if (warehouseId == null) {
                throw new IllegalArgumentException("slots 第 " + index + " 个元素的 warehouseId 为 null");
            }
            if (warehouseId.isBlank()) {
                throw new IllegalArgumentException("slots 第 " + index + " 个元素的 warehouseId 为空白：" + warehouseId);
            }

            String material = slot.material();
            if (material == null) {
                throw new IllegalArgumentException("slots 第 " + index + " 个元素的 material 为 null");
            }
            if (material.isBlank()) {
                throw new IllegalArgumentException("slots 第 " + index + " 个元素的 material 为空白：" + material);
            }

            if (slot.slotIndex() < 0) {
                throw new IllegalArgumentException("slots 第 " + index + " 个元素的 slotIndex 为负：" + slot.slotIndex());
            }
            if (slot.count() < 0L) {
                throw new IllegalArgumentException("slots 第 " + index + " 个元素的数量为负：" + slot.count());
            }

            if (!seen.add(new SlotKey(warehouseId, slot.slotIndex()))) {
                throw new IllegalArgumentException(
                        "仓库槽位重复：" + warehouseId + " 的槽 " + slot.slotIndex());
            }
        }
    }
}
