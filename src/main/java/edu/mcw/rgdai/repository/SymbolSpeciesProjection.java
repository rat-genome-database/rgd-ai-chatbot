package edu.mcw.rgdai.repository;

/**
 * One (symbol, species) pair from {@code report_object} — the shape needed to tell whether a
 * name the user gave is ambiguous across species.
 */
public interface SymbolSpeciesProjection {
    String getSymbol();
    String getSpecies();
}
