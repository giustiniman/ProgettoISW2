package it.uniroma2.isw2.openjpa.model;

public record TicketWindow(String ticketKey, int injectedVersion, int openingVersion,
                           int fixedVersion, boolean estimated, double proportionUsed) {
}
