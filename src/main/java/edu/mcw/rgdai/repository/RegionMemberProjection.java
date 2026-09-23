package edu.mcw.rgdai.repository;

/**
 * A positioned record: either the anchor whose region was asked about, or one of the records
 * overlapping it.
 */
public interface RegionMemberProjection {
    Long getRgdId();
    String getSymbol();
    String getName();
    String getChromosome();
    Long getStartPos();
    Long getStopPos();
}
