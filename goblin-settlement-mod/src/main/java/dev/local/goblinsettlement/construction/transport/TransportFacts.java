package dev.local.goblinsettlement.construction.transport;

import java.util.List;

/**
 * The traffic and link facts one inspection produces, with no text and no world access, so the
 * command and the noticeboard render the same computation twice rather than computing twice.
 */
public record TransportFacts(boolean hasSettlement, List<TransportFacts.RoadRow> roads,
                            TransportFacts.Links links) {

    /** One finished road, already sorted busiest first as the status line shows it. */
    public record RoadRow(String id, int traffic, int lanes, boolean wideningReady) {
    }

    /** Every finished link's verdict. A link in an inactive chunk is counted, never judged. */
    public record Links(int loaded, int skipped, List<TransportFacts.BrokenLink> brokenLinks) {
    }

    /**
     * A link that is not connected, and which of the two reasons it is. The reason is a boolean rather
     * than the word 受阻 or 缺口, so this record really does carry no text: the command turns it into one
     * of the two words, and the panel turns it into a translation key, each in its own presenter.
     *
     * @param blocked true when something foreign is in the way (受阻); false when a declared cell is
     *                simply missing and the rebuild path will fix it (缺口)
     */
    public record BrokenLink(String id, boolean bridge, boolean blocked) {
    }
}
