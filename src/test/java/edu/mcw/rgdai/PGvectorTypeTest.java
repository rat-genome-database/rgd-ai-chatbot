//package edu.mcw.rgdai;
//
//import com.pgvector.PGvector;
//import edu.mcw.rgdai.config.types.PGvectorType;
//import org.junit.jupiter.api.Test;
//import org.postgresql.util.PGobject;
//
//import java.sql.ResultSet;
//import java.sql.SQLException;
//
//import static org.junit.jupiter.api.Assertions.*;
//import static org.mockito.Mockito.*;
//
///**
// * Test to verify PGvectorType is working correctly
// */
//public class PGvectorTypeTest {
//
//    @Test
//    public void testNullSafeGet_ReadsVectorCorrectly() throws SQLException {
//        // Arrange
//        PGvectorType pgvectorType = new PGvectorType();
//        ResultSet mockResultSet = mock(ResultSet.class);
//
//        // Mock PostgreSQL object returning a vector string
//        PGobject pgObject = new PGobject();
//        pgObject.setType("vector");
//        pgObject.setValue("[0.1,0.2,0.3]");
//
//        when(mockResultSet.getObject(1)).thenReturn(pgObject);
//
//        // Act
//        PGvector result = pgvectorType.nullSafeGet(mockResultSet, 1, null, null);
//
//        // Assert
//        assertNotNull(result, "PGvector should not be null");
//        float[] array = result.toArray();
//        assertEquals(3, array.length, "Should have 3 elements");
//        assertEquals(0.1f, array[0], 0.001f, "First element should be 0.1");
//        assertEquals(0.2f, array[1], 0.001f, "Second element should be 0.2");
//        assertEquals(0.3f, array[2], 0.001f, "Third element should be 0.3");
//
//        System.out.println("✅ nullSafeGet() is working correctly!");
//    }
//
//    @Test
//    public void testDeepCopy_CreatesIndependentCopy() {
//        // Arrange
//        PGvectorType pgvectorType = new PGvectorType();
//        float[] original = {1.0f, 2.0f, 3.0f};
//        PGvector originalVector = new PGvector(original);
//
//        // Act
//        PGvector copy = pgvectorType.deepCopy(originalVector);
//
//        // Assert
//        assertNotNull(copy, "Copy should not be null");
//        assertNotSame(originalVector, copy, "Copy should be a different object");
//
//        float[] originalArray = originalVector.toArray();
//        float[] copyArray = copy.toArray();
//
//        assertArrayEquals(originalArray, copyArray, 0.001f, "Arrays should have same values");
//
//        System.out.println("✅ deepCopy() is working correctly!");
//    }
//
//    @Test
//    public void testEquals_ComparesVectorsCorrectly() {
//        // Arrange
//        PGvectorType pgvectorType = new PGvectorType();
//        PGvector vector1 = new PGvector(new float[]{1.0f, 2.0f});
//        PGvector vector2 = new PGvector(new float[]{1.0f, 2.0f});
//        PGvector vector3 = new PGvector(new float[]{3.0f, 4.0f});
//
//        // Act & Assert
//        assertTrue(pgvectorType.equals(vector1, vector1), "Same object should be equal");
//        assertTrue(pgvectorType.equals(vector1, vector2), "Vectors with same values should be equal");
//        assertFalse(pgvectorType.equals(vector1, vector3), "Vectors with different values should not be equal");
//        assertFalse(pgvectorType.equals(vector1, null), "Vector compared to null should not be equal");
//
//        System.out.println("✅ equals() is working correctly!");
//    }
//}
