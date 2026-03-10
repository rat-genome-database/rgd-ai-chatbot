//package edu.mcw.rgdai;
//
//import edu.mcw.scge.dao.DataSourceFactory;
//import edu.mcw.scge.dao.implementation.ClinicalTrailDAO;
//import edu.mcw.scge.datamodel.ClinicalTrialRecord;
//import org.junit.jupiter.api.Test;
//
//import javax.sql.DataSource;
//import java.sql.Connection;
//import java.sql.DatabaseMetaData;
//import java.util.List;
//
///**
// * Test to debug database connection details
// * This test helps verify which datasource is being used (JNDI vs Spring XML)
// */
//public class DatabaseConnectionTest {
//
//    @Test
//    public void testDatabaseConnection() throws Exception {
//        System.out.println("=== Testing Database Connection ===");
//
//        // Get DataSource
//        DataSource ds = DataSourceFactory.getInstance().getDataSource();
//        System.out.println("DataSource class: " + ds.getClass().getName());
//
//        // Get connection and metadata
//        try (Connection conn = ds.getConnection()) {
//            DatabaseMetaData metaData = conn.getMetaData();
//
//            System.out.println("Database URL: " + metaData.getURL());
//            System.out.println("Database User: " + metaData.getUserName());
//            System.out.println("Database Product: " + metaData.getDatabaseProductName());
//            System.out.println("Database Product Version: " + metaData.getDatabaseProductVersion());
//            System.out.println("Driver Name: " + metaData.getDriverName());
//            System.out.println("Driver Version: " + metaData.getDriverVersion());
//
//            // Check if connection is valid
//            System.out.println("Connection valid: " + conn.isValid(5));
//        }
//
//        System.out.println("=== Connection Test Complete ===");
//    }
//
//    @Test
//    public void testClinicalTrailDAOConnection() throws Exception {
//        System.out.println("=== Testing ClinicalTrailDAO Connection ===");
//
//        ClinicalTrailDAO dao = new ClinicalTrailDAO();
//        List<ClinicalTrialRecord> trials = new ClinicalTrailDAO().getAllClinicalTrailRecords();
//        System.out.println("Trials size"+trials.size());
//        DataSource ds = dao.getDataSource();
//
//        System.out.println("DataSource class from DAO: " + ds.getClass().getName());
//
//        try (Connection conn = ds.getConnection()) {
//            DatabaseMetaData metaData = conn.getMetaData();
//            System.out.println("Database URL: " + metaData.getURL());
//            System.out.println("Database User: " + metaData.getUserName());
//        }
//
//        System.out.println("=== DAO Connection Test Complete ===");
//    }
//
//    @Test
//    public void testSpringConfigProperty() {
//        System.out.println("=== Checking System Properties ===");
//
//        String springConfig = System.getProperty("spring.config");
//        String catalinaHome = System.getProperty("catalina.home");
//
//        System.out.println("spring.config: " + (springConfig != null ? springConfig : "(not set)"));
//        System.out.println("catalina.home: " + (catalinaHome != null ? catalinaHome : "(not set)"));
//
//        if (springConfig != null) {
//            System.out.println("Using Spring XML configuration");
//            java.io.File configFile = new java.io.File(springConfig);
//            System.out.println("Config file exists: " + configFile.exists());
//            System.out.println("Config file path: " + configFile.getAbsolutePath());
//        } else if (catalinaHome != null) {
//            System.out.println("Using JNDI (Tomcat context.xml)");
//        } else {
//            System.out.println("Will try to use default: properties/AppConfigure.xml");
//        }
//
//        System.out.println("=== Property Check Complete ===");
//    }
//}
