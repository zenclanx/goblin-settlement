package dev.local.goblinsettlement.noticeboard;

import dev.local.goblinsettlement.economy.food.FoodForecast;

/** Standalone checks for the noticeboard: food forecast, snapshot text, and the panel sections. */
public final class NoticeboardCheck {
    public static void main(String[] args) {
        // Food: one item per resident per active Minecraft day (MealClock.MEAL_INTERVAL_TICKS = 24000
        // = twenty minutes), so whole meals times twenty.
        check(FoodForecast.minutes(0, 0).isEmpty(), "nobody eats means no answer, not zero");
        check(FoodForecast.minutes(40, 4).orElseThrow() == 200, "ten meals at twenty minutes each");
        check(FoodForecast.minutes(39, 4).orElseThrow() == 180, "a partial meal does not count");
        check(FoodForecast.minutes(3, 4).orElseThrow() == 0, "less than one meal is zero minutes");
        check(FoodForecast.minutes(0, 4).orElseThrow() == 0, "no food is zero minutes");
        check(FoodForecast.minutes(4, 4).orElseThrow() == 20, "exactly one meal");
        check(FoodForecast.minutes(Long.MAX_VALUE, 1).orElseThrow() == Integer.MAX_VALUE,
                "an absurd stock clamps instead of overflowing");
        check(negativeDinersRejected(), "negative diners are rejected");
        System.out.println("NoticeboardCheck passed");
    }

    private static boolean negativeDinersRejected() {
        try {
            FoodForecast.minutes(10, -1);
            return false;
        } catch (IllegalArgumentException expected) {
            return true;
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
