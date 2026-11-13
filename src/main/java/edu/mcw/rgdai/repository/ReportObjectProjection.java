package edu.mcw.rgdai.repository;

/**
 * One row of {@code report_object}: the record an RGD ID names, and the report it is stored under.
 */
public interface ReportObjectProjection {
    Long getRgdId();
    String getObjectType();
    String getSymbol();
    String getSpecies();
    String getName();
    String getFileName();
}
