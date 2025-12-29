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

    public String[] getFileById(int fileId) {
        String sql = """
        SELECT
            disc_channel_id,
            disc_message_id,
            file_name
        FROM files
        WHERE file_id = ?
    """;

        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, fileId);

            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new RuntimeException("File not found for id=" + fileId);
                }

                String channelId = rs.getString("disc_channel_id");
                String messageId = rs.getString("disc_message_id");
                String fileName  = rs.getString("file_name");

                return new String[]{ channelId, messageId, fileName };
            }

        } catch (SQLException e) {
            throw new RuntimeException("Failed to fetch file metadata", e);
        }
    }

    public ResultSet getFilesByDirectoryId(int directoryId, String search, String mimeTypeFilter, String sortBy) {
        StringBuilder sql = new StringBuilder("""
        SELECT
            file_id,
            file_name,
            file_description,
            size,
            mime_type,
            created_at
        FROM files
        WHERE directory_id = ?
        """);

        if (search != null && !search.trim().isEmpty()) {
            sql.append(" AND (LOWER(file_name) LIKE ? OR LOWER(file_description) LIKE ?)");
        }

        if (mimeTypeFilter != null && !mimeTypeFilter.trim().isEmpty()) {
            sql.append(" AND mime_type LIKE ?");
        }

        switch (sortBy) {
            case "file_name":
                sql.append(" ORDER BY file_name ASC");
                break;
            case "size":
                sql.append(" ORDER BY size DESC");
                break;
            default:
                sql.append(" ORDER BY created_at DESC");
                break;
        }

        try {
            PreparedStatement ps = conn.prepareStatement(sql.toString());
            int paramIndex = 1;

            ps.setInt(paramIndex++, directoryId);

            if (search != null && !search.trim().isEmpty()) {
                String searchPattern = "%" + search.toLowerCase() + "%";
                ps.setString(paramIndex++, searchPattern);
                ps.setString(paramIndex++, searchPattern);
            }

            if (mimeTypeFilter != null && !mimeTypeFilter.trim().isEmpty()) {
                ps.setString(paramIndex++, mimeTypeFilter + "%");
            }

            return ps.executeQuery();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to fetch filtered files for directory", e);
        }
    }

    public ResultSet getAllDirectories() {
        String sql = """
        SELECT
            directory_id,
            path,
            created_at,
            (SELECT COUNT(*) FROM files WHERE directory_id = directories.directory_id) as file_count
        FROM directories
        ORDER BY path ASC
        """;

        try {
            PreparedStatement ps = conn.prepareStatement(sql);
            return ps.executeQuery();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to fetch directories", e);
        }
    }

    public ResultSet getDirectoryById(int directoryId) {
        String sql = """
        SELECT
            directory_id,
            path,
            created_at,
            (SELECT COUNT(*) FROM files WHERE directory_id = directories.directory_id) as file_count
        FROM directories
        WHERE directory_id = ?
        """;

        try {
            PreparedStatement ps = conn.prepareStatement(sql);
            ps.setInt(1, directoryId);
            return ps.executeQuery();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to fetch directory", e);
        }
    }

    public int createDirectory(String path) throws SQLException {
        String sql = """
        INSERT INTO directories (path)
        VALUES (?)
        ON CONFLICT (path) DO UPDATE SET path = EXCLUDED.path
        RETURNING directory_id
        """;

        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, path);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getInt("directory_id");
                }
                throw new SQLException("Failed to get directory ID");
            }
        }
    }

    public boolean deleteFile(int fileId) throws SQLException {
        String sql = """
        DELETE FROM files
        WHERE file_id = ?
        """;

        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, fileId);
            int rowsAffected = ps.executeUpdate();
            return rowsAffected > 0;
        }
    }

    public boolean deleteDirectory(int directoryId) throws SQLException {
        // Check if directory has files
        String checkSql = """
        SELECT COUNT(*) as file_count
        FROM files
        WHERE directory_id = ?
        """;

        try (PreparedStatement checkPs = conn.prepareStatement(checkSql)) {
            checkPs.setInt(1, directoryId);
            try (ResultSet rs = checkPs.executeQuery()) {
                if (rs.next() && rs.getInt("file_count") > 0) {
                    throw new SQLException("Cannot delete directory: contains files");
                }
            }
        }

        if (directoryId == 1) {
            throw new SQLException("Cannot delete root directory");
        }

        String deleteSql = """
        DELETE FROM directories
        WHERE directory_id = ?
        """;

        try (PreparedStatement deletePs = conn.prepareStatement(deleteSql)) {
            deletePs.setInt(1, directoryId);
            int rowsAffected = deletePs.executeUpdate();
            return rowsAffected > 0;
        }
    }

}
