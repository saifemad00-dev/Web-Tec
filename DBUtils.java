package model;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

public class DBUtils {

    private static final String URL =
            "jdbc:derby://localhost:1527/Camp_Device";


    public static Connection getConnection() throws SQLException {
        return DriverManager.getConnection(URL);
    }
}