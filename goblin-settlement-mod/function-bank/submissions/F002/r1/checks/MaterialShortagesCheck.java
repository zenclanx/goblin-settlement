package dev.local.goblinsettlement.economy.math;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * F002 独立验证程序（规格 v1）。
 *
 * <p>失败直接抛 AssertionError，不使用需要 -ea 才生效的 assert 语句，不依赖 JUnit。
 * 入口：{@code dev.local.goblinsettlement.economy.math.MaterialShortagesCheck}
 */
public final class MaterialShortagesCheck {

    private static int caseCount = 0;

    public static void main(String[] args) {
        checkAcceptanceTable();
        checkKeyOrdering();
        checkMissingStockEntries();
        checkRejectedInputs();
        checkUnmodifiableResult();
        checkInputsNotModified();
        checkResultIsSnapshot();
        checkLongExtremes();
        System.out.println("[F002] 全部通过，共 " + caseCount + " 个断言");
    }

    // ---------------------------------------------------------------- 检查项

    /** 任务卡「必须覆盖的验收案例」原表。 */
    private static void checkAcceptanceTable() {
        expect(map("wood", 10L), map("wood", 8L), map("wood", 3L),
                map("wood", 5L));

        // 预留大于库存是合法状态：可用量为 0，缺口等于全部需求。
        expect(map("stone", 4L), map("stone", 2L), map("stone", 9L),
                map("stone", 4L));

        // 需求为 0 的材料不产生条目。
        expect(map("wood", 2L, "stone", 0L), map("wood", 5L), empty(),
                empty());

        // 结果非空时也要求键的自然升序：stone 在 wood 之前。
        expectOrder(map("wood", 5L, "stone", 4L), map("wood", 1L), empty(),
                Arrays.asList("stone", "wood"),
                map("stone", 4L, "wood", 4L));

        expect(map("wood", Long.MAX_VALUE), map("wood", Long.MAX_VALUE),
                map("wood", 1L), map("wood", 1L));

        // required 为空也不跳过其他输入的校验。
        expectThrows(map(), map("unused", -1L), empty(),
                "空 required 但库存含负值");
    }

    /** 键的迭代顺序必须是 String 自然升序，大写字母排在小写字母之前。 */
    private static void checkKeyOrdering() {
        Map<String, Long> required = new LinkedHashMap<>();
        required.put("b", 1L);
        required.put("A", 1L);
        required.put("a", 1L);
        required.put("Z", 1L);
        required.put("_", 1L);   // '_' = 95，位于 'Z'(90) 与 'a'(97) 之间
        required.put("0", 1L);   // '0' = 48，排最前

        Map<String, Long> result = MaterialShortages.calculate(required, empty(), empty());
        List<String> actual = new ArrayList<>(result.keySet());
        List<String> expected = Arrays.asList("0", "A", "Z", "_", "a", "b");
        require(expected.equals(actual),
                "键顺序应为 " + expected + "，实际 " + actual);

        // 大小写不同的材料不合并、不修剪。
        Map<String, Long> near = MaterialShortages.calculate(
                map("Wood", 1L, "wood", 1L), empty(), empty());
        require(near.size() == 2, "大小写不同的键不应合并，实际 " + near);
    }

    /** 需求里出现、但库存与预留都没有的材料，库存按 0 计算。 */
    private static void checkMissingStockEntries() {
        expect(map("wood", 3L, "gold", 7L), map("wood", 10L), map("wood", 10L),
                map("wood", 3L, "gold", 7L));

        // 库存未提及但预留有记录：可用为 max(0, 0 - reserved) = 0。
        expect(map("iron", 5L), empty(), map("iron", 4L), map("iron", 5L));

        // 需求未提及但库存/预另有记录：不影响结果。
        expect(map("wood", 1L), map("wood", 1L, "extra", 99L), map("extra", 50L),
                empty());
    }

    /** null、空白键、负值等一律 IllegalArgumentException。 */
    private static void checkRejectedInputs() {
        Map<String, Long> requiredWithNullKey = new HashMap<>();
        requiredWithNullKey.put("wood", 1L);
        requiredWithNullKey.put(null, 1L);
        expectThrows(requiredWithNullKey, empty(), empty(), "required 含 null 键");

        Map<String, Long> stockWithNullKey = new HashMap<>();
        stockWithNullKey.put(null, 1L);
        expectThrows(map("wood", 1L), stockWithNullKey, empty(), "stock 含 null 键");

        Map<String, Long> reservedWithNullKey = new HashMap<>();
        reservedWithNullKey.put(null, 1L);
        expectThrows(map("wood", 1L), empty(), reservedWithNullKey, "reserved 含 null 键");

        Map<String, Long> nullValue = new HashMap<>();
        nullValue.put("wood", null);
        expectThrows(nullValue, empty(), empty(), "required 值为 null");
        expectThrows(map("wood", 1L), nullValue, empty(), "stock 值为 null");
        expectThrows(map("wood", 1L), empty(), nullValue, "reserved 值为 null");

        expectThrows(map(" ", 1L), empty(), empty(), "required 空白键(空格)");
        expectThrows(map("", 1L), empty(), empty(), "required 空白键(空串)");
        expectThrows(map("\t", 1L), empty(), empty(), "required 空白键(制表符)");
        expectThrows(map("wood", 1L), map(" \n ", 1L), empty(), "stock 空白键");
        expectThrows(map("wood", 1L), empty(), map("\u3000", 1L), "reserved 全角空格键");

        // 负值：required、stock、reserved 三处都要拒绝。
        expectThrows(map("wood", -1L), empty(), empty(), "required 负值");
        expectThrows(map("wood", 1L), map("wood", -1L), empty(), "stock 负值");
        expectThrows(map("wood", 1L), empty(), map("wood", -1L), "reserved 负值");
        expectThrows(map("ok", 1L, "bad", Long.MIN_VALUE), empty(), empty(),
                "required 含 Long.MIN_VALUE");

        // null Map。
        expectThrows(null, empty(), empty(), "required 为 null");
        expectThrows(empty(), null, empty(), "stock 为 null");
        expectThrows(empty(), empty(), null, "reserved 为 null");

        // 预留大于库存不是异常，只是可用为 0（对照：上面同样是 stock<reserved）。
        expect(map("wood", 1L), map("wood", 0L), map("wood", 5L), map("wood", 1L));
    }

    /** 返回值必须是新建且不可修改的 Map。 */
    private static void checkUnmodifiableResult() {
        Map<String, Long> result = MaterialShortages.calculate(
                map("wood", 1L), empty(), empty());

        try {
            result.put("stone", 1L);
            throw new AssertionError("结果 Map 应拒绝 put");
        } catch (UnsupportedOperationException expected) {
            caseCount++;
        }

        try {
            result.remove("wood");
            throw new AssertionError("结果 Map 应拒绝 remove");
        } catch (UnsupportedOperationException expected) {
            caseCount++;
        }

        try {
            result.clear();
            throw new AssertionError("结果 Map 应拒绝 clear");
        } catch (UnsupportedOperationException expected) {
            caseCount++;
        }

        try {
            result.putAll(map("a", 1L));
            throw new AssertionError("结果 Map 应拒绝 putAll");
        } catch (UnsupportedOperationException expected) {
            caseCount++;
        }

        // 空结果的不可修改性同样成立。
        Map<String, Long> emptyResult = MaterialShortages.calculate(empty(), empty(), empty());
        try {
            emptyResult.put("x", 1L);
            throw new AssertionError("空结果 Map 应拒绝 put");
        } catch (UnsupportedOperationException expected) {
            caseCount++;
        }
    }

    /** 计算过程不得修改任何输入集合。 */
    private static void checkInputsNotModified() {
        Map<String, Long> required = new HashMap<>(map("wood", 5L, "stone", 4L));
        Map<String, Long> stock = new HashMap<>(map("wood", 1L));
        Map<String, Long> reserved = new HashMap<>(map("wood", 0L));

        Map<String, Long> requiredCopy = new HashMap<>(required);
        Map<String, Long> stockCopy = new HashMap<>(stock);
        Map<String, Long> reservedCopy = new HashMap<>(reserved);

        MaterialShortages.calculate(required, stock, reserved);

        require(required.equals(requiredCopy), "required 被修改：" + required);
        require(stock.equals(stockCopy), "stock 被修改：" + stock);
        require(reserved.equals(reservedCopy), "reserved 被修改：" + reserved);
    }

    /** 计算后改动原输入，不得改变已返回的结果。 */
    private static void checkResultIsSnapshot() {
        Map<String, Long> required = new HashMap<>(map("wood", 10L));
        Map<String, Long> stock = new HashMap<>(map("wood", 4L));

        Map<String, Long> result = MaterialShortages.calculate(required, stock, empty());
        require(Long.valueOf(6L).equals(result.get("wood")), "初始缺口应为 6，实际 " + result);

        // 之后补货并削减需求。
        stock.put("wood", 100L);
        required.put("wood", 1L);
        required.put("gold", 50L);

        require(result.size() == 1 && Long.valueOf(6L).equals(result.get("wood")),
                "结果应是快照，实际 " + result);
    }

    /** Long 全域边界，不走 int 截断、不出现回绕。 */
    private static void checkLongExtremes() {
        expect(map("a", Long.MAX_VALUE), map("a", Long.MAX_VALUE), empty(),
                empty());
        expect(map("a", Long.MAX_VALUE), map("a", Long.MAX_VALUE),
                map("a", Long.MAX_VALUE), map("a", Long.MAX_VALUE));
        expect(map("a", Long.MAX_VALUE), empty(), empty(),
                map("a", Long.MAX_VALUE));
        expect(map("a", 1L), map("a", Long.MAX_VALUE), map("a", Long.MAX_VALUE - 1),
                empty());

        // 巨大可用量不应回绕成负数而制造出虚假的巨大缺口。
        Map<String, Long> big = MaterialShortages.calculate(
                map("a", 1L), map("a", Long.MAX_VALUE), map("a", 0L));
        require(big.isEmpty(), "巨大可用量时不应有缺口，实际 " + big);
    }

    // ---------------------------------------------------------------- 工具

    private static void expect(Map<String, Long> required, Map<String, Long> stock,
                               Map<String, Long> reserved, Map<String, Long> wanted) {
        Map<String, Long> actual = MaterialShortages.calculate(required, stock, reserved);
        require(wanted.equals(actual), "期望 " + wanted + " 实际 " + actual);
    }

    private static void expectOrder(Map<String, Long> required, Map<String, Long> stock,
                                    Map<String, Long> reserved,
                                    List<String> wantedOrder, Map<String, Long> wanted) {
        Map<String, Long> actual = MaterialShortages.calculate(required, stock, reserved);
        require(wanted.equals(actual), "期望 " + wanted + " 实际 " + actual);
        List<String> actualOrder = new ArrayList<>(actual.keySet());
        require(wantedOrder.equals(actualOrder),
                "期望顺序 " + wantedOrder + " 实际 " + actualOrder);
    }

    private static void expectThrows(Map<String, Long> required, Map<String, Long> stock,
                                     Map<String, Long> reserved, String scenario) {
        try {
            MaterialShortages.calculate(required, stock, reserved);
        } catch (IllegalArgumentException expected) {
            caseCount++;
            return;
        }
        throw new AssertionError("应抛 IllegalArgumentException：" + scenario);
    }

    private static Map<String, Long> empty() {
        return new HashMap<>();
    }

    private static Map<String, Long> map(Object... pairs) {
        Map<String, Long> result = new HashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            result.put((String) pairs[i], (Long) pairs[i + 1]);
        }
        return result;
    }

    private static void require(boolean condition, String message) {
        caseCount++;
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
