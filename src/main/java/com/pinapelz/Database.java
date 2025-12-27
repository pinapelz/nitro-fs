/*
Postgres will serve as the index for managing all the files. Iteration through all messages is too slow
 */
package com.pinapelz;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.util.Properties;

public class Database {

    private Connection conn;

    public Database(String host, String user, String password, String db){
        try {
            conn = createDBConnection(host, user, password, db);
            System.out.println("[Database] Running schema.sql as necessary");
            String schemaSQL = Files.readString(Path.of("schema.sql"));
            Statement statement = conn.createStatement();
            statement.execute(schemaSQL);
        } catch (IOException | SQLException e) {
            throw new RuntimeException(e);
        }

    }

    public static Connection createDBConnection(String host, String user, String password, String db) throws IOException, SQLException {
        String url = "jdbc:postgresql://"+host+"/"+db+"?sslmode=require&channel_binding=require";
        Properties props = new Properties();
        props.setProperty("user", user);
        props.setProperty("password", password);
        return DriverManager.getConnection(url, props);
    }

    public void recordFileMetadata(String channelId, String messageId, int rootDirId, String fileName, String description, int size, String mimeType) throws SQLException {
        PreparedStatement ps = conn.prepareStatement("""
    INSERT INTO files (
        disc_channel_id,
        disc_message_id,
        directory_id,
        file_name,
        file_description,
        size,
        mime_type
    )
    VALUES (?, ?, ?, ?, ?, ?, ?)
""");
        ps.setString(1, channelId);
        ps.setString(2, messageId);
        ps.setLong(3, rootDirId);
        ps.setString(4, fileName);
        ps.setString(5, description);
        ps.setLong(6, size);
        ps.setString(7, mimeType);
        ps.executeUpdate();

    }
}
