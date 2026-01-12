/*
Postgres will serve as the index for managing all the files. Iteration through all messages is too slow
 */
package com.pinapelz;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.sql.Types;
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

    public ResultSet getUniqueOriginalFilesFromPartials(int directoryId, String search) {
        StringBuilder sql = new StringBuilder("""
            SELECT DISTINCT 
                original_filename,
                mime_type,
                directory_id,
                MAX(created_at) as created_at,
                SUM(part_size) as size,
                MAX(file_description) as file_description
            FROM file_partials
            WHERE directory_id = ?
        """);

        if (search != null && !search.trim().isEmpty()) {
            sql.append(" AND LOWER(original_filename) LIKE ?");
        }

        sql.append("""
            GROUP BY original_filename, mime_type, directory_id
            ORDER BY original_filename ASC
        """);

        try {
            PreparedStatement ps = conn.prepareStatement(sql.toString());
            int paramIndex = 1;
            ps.setInt(paramIndex++, directoryId);

            if (search != null && !search.trim().isEmpty()) {
                ps.setString(paramIndex++, "%" + search.toLowerCase() + "%");
            }

            return ps.executeQuery();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to fetch unique original files from partials", e);
        }
    }

    public boolean deleteDirectory(int directoryId) throws SQLException {
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

    public long recordFilePartial(String channelId, String messageId, int directoryId, 
                                 String partName, int partNumber, long partSize, 
                                 String originalFilename, String description, String mimeType) throws SQLException {
        String sql = """
        INSERT INTO file_partials (
            disc_channel_id,
            disc_message_id,
            directory_id,
            part_name,
            part_number,
            part_size,
            original_filename,
            file_description,
            mime_type,
            uploaded_via_webhook
        )
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        RETURNING partial_id
        """;

        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, channelId);
            ps.setString(2, messageId);
            ps.setInt(3, directoryId);
            ps.setString(4, partName);
            ps.setInt(5, partNumber);
            ps.setLong(6, partSize);
            ps.setString(7, originalFilename);
            ps.setString(8, description);
            ps.setString(9, mimeType);
            ps.setBoolean(10, true);

            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getLong("partial_id");
                }
                throw new SQLException("Failed to get partial ID");
            }
        }
    }

    public boolean checkPartialExists(String partName, int directoryId) throws SQLException {
        String sql = """
        SELECT COUNT(*) as count
        FROM file_partials
        WHERE part_name = ? AND directory_id = ?
        """;

        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, partName);
            ps.setInt(2, directoryId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getInt("count") > 0;
                }
                return false;
            }
        }
    }

    public boolean deleteFilePartials(String originalFilename, int directoryId) throws SQLException {
        String sql = """
        DELETE FROM file_partials
        WHERE original_filename = ? AND directory_id = ?
        """;

        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, originalFilename);
            ps.setInt(2, directoryId);
            int rowsAffected = ps.executeUpdate();
            return rowsAffected > 0;
        }
    }

    public ResultSet getFilePartialsByOriginalFilename(String originalFilename, int directoryId) {
        String sql = """
        SELECT
            partial_id,
            disc_channel_id,
            disc_message_id,
            part_name,
            part_number,
            part_size,
            original_filename,
            mime_type,
            uploaded_via_webhook,
            created_at
        FROM file_partials
        WHERE original_filename = ? AND directory_id = ?
        ORDER BY part_number ASC
        """;

        try {
            PreparedStatement ps = conn.prepareStatement(sql);
            ps.setString(1, originalFilename);
            ps.setInt(2, directoryId);
            return ps.executeQuery();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to fetch file partials", e);
        }
    }

}
