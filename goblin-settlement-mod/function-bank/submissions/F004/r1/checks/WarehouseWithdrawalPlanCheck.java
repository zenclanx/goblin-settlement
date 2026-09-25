package dev.local.goblinsettlement.economy.math;

import dev.local.goblinsettlement.economy.math.WarehouseWithdrawalPlan.Plan;
import dev.local.goblinsettlement.economy.math.WarehouseWithdrawalPlan.Slot;
import dev.local.goblinsettlement.economy.math.WarehouseWithdrawalPlan.Withdrawal;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * F004 独立验证程序（规格 v1）。
 *
 * <p>失败直接抛 AssertionError，不使用需要 -ea 才生效的 assert 语句，不依赖 JUnit。
 * 入口：{@code dev.local.goblinsettlement.economy.math.WarehouseWithdrawalPlanCheck}
 */
public final class WarehouseWithdrawalPlanCheck {

    private static int caseCount = 0;

    public static void main(String[] args) {
        checkFirstAcceptanceCase();
        checkSecondAcceptanceCase();
        checkOrdering();
        checkZeroCountSlotsAndNoOverWithdrawal();
        checkZeroDemandMaterials();
        checkIncompleteWithholdsWithdrawals();
        checkEmptyRequiredStillValidates();
        checkDuplicateSlotRejection();
        checkValidationOfNullAndBlank();
        checkValidationOfNegatives();
        checkLongExtremes();
        checkUnmodifiableResults();
        checkInputNotModified();
        checkResultIsSnapshot();
        System.out.println("[F004] 全部通过，共 " + caseCount + " 个断言");
    }

    // ---------------------------------------------------------------- 验收案例

    /** 需求 {oak:5}，槽 (B,0,oak,4) (A,2,oak,2) (A,0,oak,1)：A/0 取 1、A/2 取 2、B/0 取 2，完整。 */
    private static void checkFirstAcceptanceCase() {
        Plan plan = WarehouseWithdrawalPlan.calculate(
                required("oak", 5L),
                slots(slot("B", 0, "oak", 4L), slot("A", 2, "oak", 2L), slot("A", 0, "oak", 1L)));

        require(plan.complete(), "案例 1 应完整，实际 " + plan);
        require(plan.missing().isEmpty(), "案例 1 缺口应为空，实际 " + plan.missing());

        List<Withdrawal> expected = withdrawals(
                withdrawal("A", 0, "oak", 1L),
                withdrawal("A", 2, "oak", 2L),
                withdrawal("B", 0, "oak", 2L));
        require(expected.equals(plan.withdrawals()),
                "案例 1 取料清单应为 " + expected + "，实际 " + plan.withdrawals());
    }

    /** 需求 {oak:6,stone:3}，只有 oak 5 与 stone 3：不完整、取料清单为空、缺口 {oak:1}。 */
    private static void checkSecondAcceptanceCase() {
        Plan plan = WarehouseWithdrawalPlan.calculate(
                required("oak", 6L, "stone", 3L),
                slots(slot("A", 0, "oak", 5L), slot("A", 1, "stone", 3L)));

        require(!plan.complete(), "案例 2 应不完整");
        require(plan.withdrawals().isEmpty(),
                "案例 2 取料清单必须为空，实际 " + plan.withdrawals());
        require(required("oak", 1L).equals(plan.missing()),
                "案例 2 缺口应为 {oak:1}，实际 " + plan.missing());
    }

    // ---------------------------------------------------------------- 处理顺序

    /** 先按材料名升序，同材料内先按仓库 ID 升序、再按槽号升序，与输入顺序无关。 */
    private static void checkOrdering() {
        // 材料升序：oak 在 stone 之前。
        Plan byMaterial = WarehouseWithdrawalPlan.calculate(
                required("stone", 1L, "oak", 2L),
                slots(slot("A", 1, "stone", 1L), slot("A", 0, "oak", 2L)));
        require(withdrawals(withdrawal("A", 0, "oak", 2L), withdrawal("A", 1, "stone", 1L))
                        .equals(byMaterial.withdrawals()),
                "材料应按自然升序处理，实际 " + byMaterial.withdrawals());

        // 同一材料：仓库 ID 升序优先于槽号升序。输入刻意打乱。
        Plan byWarehouse = WarehouseWithdrawalPlan.calculate(
                required("oak", 3L),
                slots(slot("B", 5, "oak", 1L), slot("A", 9, "oak", 1L), slot("A", 2, "oak", 1L)));
        require(withdrawals(
                        withdrawal("A", 2, "oak", 1L),
                        withdrawal("A", 9, "oak", 1L),
                        withdrawal("B", 5, "oak", 1L))
                        .equals(byWarehouse.withdrawals()),
                "应先按仓库 ID 再按槽号取料，实际 " + byWarehouse.withdrawals());

        // 换个输入顺序，结果必须一致。
        Plan reversed = WarehouseWithdrawalPlan.calculate(
                required("oak", 3L),
                slots(slot("A", 2, "oak", 1L), slot("A", 9, "oak", 1L), slot("B", 5, "oak", 1L)));
        require(byWarehouse.withdrawals().equals(reversed.withdrawals()),
                "结果应与输入顺序无关：" + byWarehouse.withdrawals() + " vs " + reversed.withdrawals());

        // 大写字母的仓库 ID 排在小写之前（String 自然顺序）。
        Plan mixedCase = WarehouseWithdrawalPlan.calculate(
                required("oak", 2L),
                slots(slot("a", 0, "oak", 1L), slot("B", 0, "oak", 1L)));
        require(withdrawals(withdrawal("B", 0, "oak", 1L), withdrawal("a", 0, "oak", 1L))
                        .equals(mixedCase.withdrawals()),
                "仓库 ID 应按 String 自然顺序，实际 " + mixedCase.withdrawals());
    }

    /** 数量为 0 的槽不参与取料；单槽不会被超取。 */
    private static void checkZeroCountSlotsAndNoOverWithdrawal() {
        // 0 数量的槽被跳过，即使槽号更靠前。
        Plan plan = WarehouseWithdrawalPlan.calculate(
                required("oak", 1L),
                slots(slot("A", 0, "oak", 0L), slot("A", 1, "oak", 5L)));
        require(withdrawals(withdrawal("A", 1, "oak", 1L)).equals(plan.withdrawals()),
                "数量为 0 的槽不应参与取料，实际 " + plan.withdrawals());
        require(plan.complete(), "应完整");

        // 不超取：槽里 100 个，只需要 1 个。
        Plan noOverDraw = WarehouseWithdrawalPlan.calculate(
                required("oak", 1L), slots(slot("A", 0, "oak", 100L)));
        require(withdrawals(withdrawal("A", 0, "oak", 1L)).equals(noOverDraw.withdrawals()),
                "不应超取，实际 " + noOverDraw.withdrawals());

        // 材料名必须完全相同才算匹配，不合并、不忽略大小写。
        Plan exactName = WarehouseWithdrawalPlan.calculate(
                required("oak", 1L), slots(slot("A", 0, "Oak", 100L)));
        require(!exactName.complete(), "大小写不同的材料名不应匹配");
        require(required("oak", 1L).equals(exactName.missing()),
                "大小写不同的材料名应计入缺口，实际 " + exactName.missing());
    }

    /** 需求为 0 的材料不生成取料行，也不进入缺口。 */
    private static void checkZeroDemandMaterials() {
        Plan plan = WarehouseWithdrawalPlan.calculate(
                required("oak", 0L), slots(slot("A", 0, "oak", 100L)));
        require(plan.complete(), "零需求应视为已满足");
        require(plan.withdrawals().isEmpty(), "零需求不应生成取料行，实际 " + plan.withdrawals());
        require(plan.missing().isEmpty(), "零需求不应产生缺口");
    }

    /** 任一材料不足时，整体不完整且不给出部分取料清单。 */
    private static void checkIncompleteWithholdsWithdrawals() {
        // oak 完全够、stone 差 1：仍然不能给出 oak 的取料行。
        Plan plan = WarehouseWithdrawalPlan.calculate(
                required("oak", 2L, "stone", 6L),
                slots(slot("A", 0, "oak", 10L), slot("A", 1, "stone", 5L)));
        require(!plan.complete(), "存在缺口时应不完整");
        require(plan.withdrawals().isEmpty(),
                "不完整方案不得包含取料行，实际 " + plan.withdrawals());
        require(required("stone", 1L).equals(plan.missing()),
                "足量的 oak 不应进入缺口，只应缺 stone 1，实际 " + plan.missing());

        // 多个缺口时按键自然升序：oak 在 stone 之前。
        Plan multiple = WarehouseWithdrawalPlan.calculate(
                required("stone", 4L, "oak", 7L), slots());
        require(!multiple.complete(), "应不完整");
        require(multiple.withdrawals().isEmpty(), "不得包含取料行");
        require(orderOf(multiple.missing()).equals(Arrays.asList("oak", "stone")),
                "缺口键应按自然升序，实际 " + orderOf(multiple.missing()));
        require(required("oak", 7L, "stone", 4L).equals(multiple.missing()),
                "缺口数值应为 {oak:7,stone:4}，实际 " + multiple.missing());

        // 完全没有槽位时，全部需求都成为缺口。
        Plan noSlots = WarehouseWithdrawalPlan.calculate(required("oak", 3L), slots());
        require(required("oak", 3L).equals(noSlots.missing()), "无槽位时缺口应为全部需求");
    }

    /** required 为空时仍要完整校验 slots。 */
    private static void checkEmptyRequiredStillValidates() {
        // 空需求 + 负数量槽位。
        expectThrows(required(), slots(slot("A", 0, "oak", -1L)), "空 required 但槽位数量为负");
        // 空需求 + null 元素。
        expectThrows(required(), nullElementSlots(), "空 required 但槽位元素为 null");
        // 空需求 + 重复 (warehouseId, slotIndex)。
        expectThrows(required(),
                slots(slot("A", 0, "oak", 1L), slot("A", 0, "stone", 1L)),
                "空 required 但仓库槽号重复");
        // 空需求 + null slots。
        expectThrows(required(), null, "空 required 但 slots 为 null");

        // 合法情形：空需求返回完整且无取料行。
        Plan plan = WarehouseWithdrawalPlan.calculate(required(), slots(slot("A", 0, "oak", 1L)));
        require(plan.complete(), "空需求应完整");
        require(plan.withdrawals().isEmpty(), "空需求不应有取料行");
        require(plan.missing().isEmpty(), "空需求不应有缺口");
    }

    // ---------------------------------------------------------------- 校验

    /** 同一 (warehouseId, slotIndex) 重复即非法，与材料名无关；不同仓库同槽号合法。 */
    private static void checkDuplicateSlotRejection() {
        // 同仓库同槽号、材料不同 —— 仍然非法。
        expectThrows(required("oak", 1L),
                slots(slot("A", 0, "oak", 1L), slot("A", 0, "stone", 1L)),
                "同仓库同槽号不同材料");
        // 同仓库同槽号、材料相同。
        expectThrows(required("oak", 1L),
                slots(slot("A", 3, "oak", 1L), slot("A", 3, "oak", 2L)),
                "同仓库同槽号同材料");
        // 三连重复。
        expectThrows(required("oak", 1L),
                slots(slot("A", 3, "oak", 1L), slot("A", 3, "oak", 1L), slot("A", 3, "oak", 1L)),
                "同仓库同槽号三连重复");

        // 不同仓库的同槽号合法。
        Plan plan = WarehouseWithdrawalPlan.calculate(
                required("oak", 2L),
                slots(slot("A", 0, "oak", 1L), slot("B", 0, "oak", 1L)));
        require(plan.complete(), "不同仓库的同槽号应合法");
        require(withdrawals(withdrawal("A", 0, "oak", 1L), withdrawal("B", 0, "oak", 1L))
                        .equals(plan.withdrawals()),
                "不同仓库同槽号应各自取料，实际 " + plan.withdrawals());

        // 大小写不同的仓库 ID 视为不同仓库。
        Plan caseSensitive = WarehouseWithdrawalPlan.calculate(
                required("oak", 2L),
                slots(slot("a", 0, "oak", 1L), slot("A", 0, "oak", 1L)));
        require(caseSensitive.complete(), "仓库 ID 区分大小写，不应判为重复");
    }

    /** null 与空白字符串的拒绝。 */
    private static void checkValidationOfNullAndBlank() {
        // required：null map、null 键、空白键、null 值。
        expectThrows(null, slots(), "required 为 null");
        expectThrows(nullKeyRequired(), slots(), "required 含 null 键");
        expectThrows(required(" ", 1L), slots(), "required 含空格键");
        expectThrows(required("", 1L), slots(), "required 含空串键");
        expectThrows(required("\t", 1L), slots(), "required 含制表符键");
        expectThrows(nullValueRequired(), slots(), "required 值为 null");

        // slots：null 列表、null 元素。
        expectThrows(required("oak", 1L), null, "slots 为 null");
        expectThrows(required("oak", 1L), nullElementSlots(), "slots 含 null 元素");

        // slot 字段：null warehouseId、空白 warehouseId、null material、空白 material。
        expectThrows(required("oak", 1L), slots(slot(null, 0, "oak", 1L)), "warehouseId 为 null");
        expectThrows(required("oak", 1L), slots(slot(" ", 0, "oak", 1L)), "warehouseId 为空白");
        expectThrows(required("oak", 1L), slots(slot("\u3000", 0, "oak", 1L)), "warehouseId 为全角空格");
        expectThrows(required("oak", 1L), slots(slot("A", 0, null, 1L)), "material 为 null");
        expectThrows(required("oak", 1L), slots(slot("A", 0, " ", 1L)), "material 为空白");
        expectThrows(required("oak", 1L), slots(slot("A", 0, "", 1L)), "material 为空串");

        // 第二个槽位非法时也要被发现（不是只查第一个）。
        expectThrows(required("oak", 1L),
                slots(slot("A", 0, "oak", 1L), slot(null, 1, "oak", 1L)),
                "第二个槽位 warehouseId 为 null");
    }

    /** 负数与越界槽号的拒绝。 */
    private static void checkValidationOfNegatives() {
        expectThrows(required("oak", -1L), slots(), "required 数量为负");
        expectThrows(required("oak", Long.MIN_VALUE), slots(), "required 为 Long.MIN_VALUE");

        expectThrows(required("oak", 1L), slots(slot("A", -1, "oak", 1L)), "slotIndex 为负");
        expectThrows(required("oak", 1L), slots(slot("A", Integer.MIN_VALUE, "oak", 1L)),
                "slotIndex 为 int 最小值");
        expectThrows(required("oak", 1L), slots(slot("A", 0, "oak", -1L)), "数量为负");
        expectThrows(required("oak", 1L), slots(slot("A", 0, "oak", Long.MIN_VALUE)),
                "数量为 Long.MIN_VALUE");

        // 未被 required 提到的材料，其非法槽位同样要被拒绝。
        expectThrows(required("oak", 1L),
                slots(slot("A", 0, "oak", 1L), slot("B", 0, "unused", -5L)),
                "未被需求用到的材料数量为负");
    }

    // ---------------------------------------------------------------- Long 边界

    /** Long 全域：单槽与多槽的 Long.MAX_VALUE 都不能溢出。 */
    private static void checkLongExtremes() {
        long max = Long.MAX_VALUE;

        // 单槽恰好满足。
        Plan single = WarehouseWithdrawalPlan.calculate(required("oak", max), slots(slot("A", 0, "oak", max)));
        require(single.complete(), "单槽 Long.MAX_VALUE 应完整");
        require(withdrawals(withdrawal("A", 0, "oak", max)).equals(single.withdrawals()),
                "单槽 Long.MAX_VALUE 取料应精确，实际 " + single.withdrawals());

        // 两个槽都是 Long.MAX_VALUE：先汇总必然溢出，必须逐个相减。
        Plan twoSlots = WarehouseWithdrawalPlan.calculate(
                required("oak", max),
                slots(slot("A", 0, "oak", max), slot("A", 1, "oak", max)));
        require(twoSlots.complete(), "两槽 Long.MAX_VALUE 应完整");
        require(twoSlots.withdrawals().size() == 1, "第一个槽已满足全部需求，只应有一条取料行，实际 "
                + twoSlots.withdrawals());
        require(withdrawals(withdrawal("A", 0, "oak", max)).equals(twoSlots.withdrawals()),
                "第一个槽应被取满，实际 " + twoSlots.withdrawals());

        // 跨两个槽、贴近上界的精确相加。
        Plan split = WarehouseWithdrawalPlan.calculate(
                required("oak", max),
                slots(slot("A", 0, "oak", max - 1L), slot("A", 1, "oak", 2L)));
        require(split.complete(), "跨槽取料应完整");
        require(withdrawals(
                        withdrawal("A", 0, "oak", max - 1L),
                        withdrawal("A", 1, "oak", 1L))
                        .equals(split.withdrawals()),
                "跨槽取料应精确，实际 " + split.withdrawals());

        // 差一个就不够：缺口精确为 1。
        Plan shortByOne = WarehouseWithdrawalPlan.calculate(
                required("oak", max),
                slots(slot("A", 0, "oak", max - 1L)));
        require(!shortByOne.complete(), "少 1 个应判为不完整");
        require(required("oak", 1L).equals(shortByOne.missing()),
                "缺口应精确为 1，实际 " + shortByOne.missing());

        // 需求为 Long.MAX_VALUE 且完全无库存。
        Plan nothing = WarehouseWithdrawalPlan.calculate(required("oak", max), slots());
        require(required("oak", max).equals(nothing.missing()), "无库存时缺口应为全部需求");
    }

    // ---------------------------------------------------------------- 快照语义

    /** 返回的 List 与 Map 必须拒绝修改。 */
    private static void checkUnmodifiableResults() {
        Plan complete = WarehouseWithdrawalPlan.calculate(required("oak", 1L), slots(slot("A", 0, "oak", 1L)));
        try {
            complete.withdrawals().add(withdrawal("B", 0, "oak", 1L));
            throw new AssertionError("取料清单应拒绝 add");
        } catch (UnsupportedOperationException expected) {
            caseCount++;
        }
        try {
            complete.withdrawals().remove(0);
            throw new AssertionError("取料清单应拒绝 remove");
        } catch (UnsupportedOperationException expected) {
            caseCount++;
        }
        try {
            complete.missing().put("oak", 1L);
            throw new AssertionError("完整方案的 missing 应拒绝 put");
        } catch (UnsupportedOperationException expected) {
            caseCount++;
        }

        Plan incomplete = WarehouseWithdrawalPlan.calculate(required("oak", 5L), slots());
        try {
            incomplete.missing().put("stone", 1L);
            throw new AssertionError("缺口 Map 应拒绝 put");
        } catch (UnsupportedOperationException expected) {
            caseCount++;
        }
        try {
            incomplete.missing().remove("oak");
            throw new AssertionError("缺口 Map 应拒绝 remove");
        } catch (UnsupportedOperationException expected) {
            caseCount++;
        }
        try {
            incomplete.withdrawals().add(withdrawal("A", 0, "oak", 1L));
            throw new AssertionError("不完整方案的取料清单应拒绝 add");
        } catch (UnsupportedOperationException expected) {
            caseCount++;
        }
    }

    /** 输入集合不得被修改。 */
    private static void checkInputNotModified() {
        Map<String, Long> required = required("oak", 5L, "stone", 2L);
        List<Slot> slots = slots(slot("A", 0, "oak", 4L), slot("B", 0, "oak", 4L));
        Map<String, Long> requiredCopy = new HashMap<>(required);
        List<Slot> slotsCopy = new ArrayList<>(slots);

        WarehouseWithdrawalPlan.calculate(required, slots);

        require(required.equals(requiredCopy), "required 被修改：" + required);
        require(slots.equals(slotsCopy), "slots 被修改：" + slots);
    }

    /** 计算后改动输入，已返回的结果不变。 */
    private static void checkResultIsSnapshot() {
        Map<String, Long> required = required("oak", 5L);
        List<Slot> slots = slots(slot("A", 0, "oak", 5L));
        List<Withdrawal> slotsBackup = new ArrayList<>(slots);

        Plan plan = WarehouseWithdrawalPlan.calculate(required, slots);
        require(withdrawals(withdrawal("A", 0, "oak", 5L)).equals(plan.withdrawals()),
                "初始结果错误：" + plan.withdrawals());

        // 之后需求变大、库存变多、并追加槽位。
        required.put("oak", 999L);
        required.put("stone", 1L);
        slots.clear();
        slots.add(slot("Z", 7, "oak", 1L));

        require(withdrawals(withdrawal("A", 0, "oak", 5L)).equals(plan.withdrawals()),
                "结果应是快照，实际 " + plan.withdrawals());
        require(plan.complete() && plan.missing().isEmpty(),
                "结果应是快照，实际 complete=" + plan.complete() + " missing=" + plan.missing());
        require(slotsBackup.size() == 1, "检查程序自身状态不应被影响");
    }

    // ---------------------------------------------------------------- 工具

    private static void require(boolean condition, String message) {
        caseCount++;
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static void expectThrows(Map<String, Long> required, List<Slot> slots, String scenario) {
        try {
            WarehouseWithdrawalPlan.calculate(required, slots);
        } catch (IllegalArgumentException expected) {
            caseCount++;
            return;
        }
        throw new AssertionError("应抛 IllegalArgumentException：" + scenario);
    }

    private static Map<String, Long> required(Object... pairs) {
        Map<String, Long> map = new HashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            map.put((String) pairs[i], (Long) pairs[i + 1]);
        }
        return map;
    }

    private static Map<String, Long> nullKeyRequired() {
        Map<String, Long> map = new HashMap<>();
        map.put("oak", 1L);
        map.put(null, 1L);
        return map;
    }

    private static Map<String, Long> nullValueRequired() {
        Map<String, Long> map = new HashMap<>();
        map.put("oak", null);
        return map;
    }

    private static Slot slot(String warehouseId, int slotIndex, String material, long count) {
        return new Slot(warehouseId, slotIndex, material, count);
    }

    private static List<Slot> slots(Slot... items) {
        return new ArrayList<>(Arrays.asList(items));
    }

    /** 含 null 元素的槽位列表；Arrays.asList 允许 null，ArrayList 也允许。 */
    private static List<Slot> nullElementSlots() {
        List<Slot> list = new ArrayList<>();
        list.add(slot("A", 0, "oak", 1L));
        list.add(null);
        return list;
    }

    private static Withdrawal withdrawal(String warehouseId, int slotIndex, String material, long count) {
        return new Withdrawal(warehouseId, slotIndex, material, count);
    }

    private static List<Withdrawal> withdrawals(Withdrawal... items) {
        return new ArrayList<>(Arrays.asList(items));
    }

    private static List<String> orderOf(Map<String, Long> map) {
        return new ArrayList<>(map.keySet());
    }
}
