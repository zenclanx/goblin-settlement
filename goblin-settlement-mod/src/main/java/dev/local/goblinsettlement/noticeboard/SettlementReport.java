package dev.local.goblinsettlement.noticeboard;

import dev.local.goblinsettlement.colony.Profession;
import dev.local.goblinsettlement.construction.transport.TransportFacts;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import net.minecraft.core.BlockPos;

/**
 * One settlement's state, as facts rather than finished text. Two presenters render it -- the status
 * command and the noticeboard panel -- so a new figure is added once and both show it.
 */
public record SettlementReport(Header header, Stock stock, Housing housing, TransportFacts traffic,
                               Optional<BlockPos> nextTarget, Map<Profession, Integer> trades) {

    /** Who lives here and how much room is claimed for them. */
    public record Header(String id, BlockPos anchor, int adults, int children, int occupiedSlots,
                         int maxResidents, int plots, int maxPlots, int playerAreas) {
    }

    /** The known public stock. foodMinutes is empty when nobody eats; the counts are a lower bound. */
    public record Stock(int food, int foodTarget, OptionalInt foodMinutes, int seeds,
                        int seedTarget, int hoes, int axes, int pickaxes, int containers,
                        boolean complete, String priority) {
    }

    /** Beds, occupancy, and one row per judged home with the reason it cannot advance. */
    public record Housing(int beds, int occupied, int spare, int homeless, List<HomeRow> homes,
                          int notLoaded, boolean blueprintUnavailable) {
    }

    public record HomeRow(BlockPos bed, int occupancy, int capacity, Optional<String> reason) {
    }
}
