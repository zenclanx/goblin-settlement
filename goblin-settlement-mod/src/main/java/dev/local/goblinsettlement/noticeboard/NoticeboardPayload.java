package dev.local.goblinsettlement.noticeboard;

import dev.local.goblinsettlement.GoblinSettlement;
import java.util.Optional;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * One settlement snapshot, sent by the server that computed it to the client that will draw it.
 * Empty is a real payload, not an error: a dimension with no settlement still opens a panel that
 * says so.
 */
public record NoticeboardPayload(Optional<SettlementReport> report) implements CustomPacketPayload {
    // Built from the mod's own namespace on purpose: CustomPacketPayload.createType would file the id
    // under minecraft: instead.
    public static final CustomPacketPayload.Type<NoticeboardPayload> TYPE =
            new CustomPacketPayload.Type<>(
                    Identifier.fromNamespaceAndPath(GoblinSettlement.MOD_ID, "noticeboard"));
    public static final StreamCodec<FriendlyByteBuf, NoticeboardPayload> CODEC =
            SettlementReport.OPTIONAL_CODEC.map(NoticeboardPayload::new, NoticeboardPayload::report);

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
