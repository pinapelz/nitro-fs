package com.pinapelz;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

public class Database {

    private final DataSource ds;

    public record FileEntry(
            int fileId,
            String fileName,
            String description,
            long size,
            String mimeType,
            OffsetDateTime createdAt
    ) {}

    public record DirectoryEntry(
            int directoryId,
            String path,
            OffsetDateTime createdAt,
            int fileCount
    ) {}

    public record FilePartialEntry(
            long partialId,
            String channelId,
            String messageId,
            String partName,
            int partNumber,
            long partSize,
            String originalFilename,
            String mimeType,
            boolean uploadedViaWebhook,
            OffsetDateTime createdAt
    ) {}

    public record PartialGroupEntry(
            String originalFilename,
            String mimeType,
            int directoryId,
            OffsetDateTime createdAt,
            long size,
            String description
    ) {}

    public Database(String host, String user, String password, String db) {
        try {
            ds = createDataSource(host, user, password, db);
            try (Connection c = ds.getConnection();
                 Statement s = c.createStatement()) {
                s.execute(Files.readString(Path.of("schema.sql")));
            }
        } catch (IOException | SQLException e) {
            throw new RuntimeException(e);
        }
    }

    private static DataSource createDataSource(String host, String user, String pass, String db) {
        HikariConfig c = new HikariConfig();
        c.setJdbcUrl("jdbc:postgresql://" + host + "/" + db + "?sslmode=require&channel_binding=require");
        c.setUsername(user);
        c.setPassword(pass);
        c.setMaximumPoolSize(3);
        c.setMinimumIdle(1);
        c.setIdleTimeout(60_000);
        c.setMaxLifetime(600_000);
        c.addDataSourceProperty("reWriteBatchedInserts", "true");
        return new HikariDataSource(c);
    }

    public void recordFileMetadata(
            String channelId, String messageId, int dirId,
            String name, String desc, int size, String mime
    ) throws SQLException {

        String sql = """
            INSERT INTO files
            (disc_channel_id, disc_message_id, directory_id,
             file_name, file_description, size, mime_type)
            VALUES (?, ?, ?, ?, ?, ?, ?)
        """;

        try (Connection c = ds.getConnection();
             PreparedStatement p = c.prepareStatement(sql)) {

            p.setString(1, channelId);
            p.setString(2, messageId);
            p.setInt(3, dirId);
            p.setString(4, name);
            p.setString(5, desc);
            p.setInt(6, size);
            p.setString(7, mime);
            p.executeUpdate();
        }
    }

    public String[] getFileById(int fileId) {
        String sql = """
            SELECT disc_channel_id, disc_message_id, file_name
            FROM files WHERE file_id = ?
        """;

        try (Connection c = ds.getConnection();
             PreparedStatement p = c.prepareStatement(sql)) {

            p.setInt(1, fileId);
            try (ResultSet r = p.executeQuery()) {
                if (!r.next()) throw new RuntimeException();
                return new String[]{ r.getString(1), r.getString(2), r.getString(3) };
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public List<FileEntry> getFilesByDirectoryId(
            int dirId, String search, String mime, String sort
    ) {

        StringBuilder q = new StringBuilder("""
            SELECT file_id, file_name, file_description,
                   size, mime_type, created_at
            FROM files WHERE directory_id = ?
        """);

        if (search != null && !search.isBlank())
            q.append(" AND (LOWER(file_name) LIKE ? OR LOWER(file_description) LIKE ?)");
        if (mime != null && !mime.isBlank())
            q.append(" AND mime_type LIKE ?");

        q.append(" ORDER BY ")
                .append("size".equals(sort) ? "size DESC" :
                        "file_name".equals(sort) ? "file_name ASC" :
                                "created_at DESC");

        try (Connection c = ds.getConnection();
             PreparedStatement p = c.prepareStatement(q.toString())) {

            int i = 1;
            p.setInt(i++, dirId);

            if (search != null && !search.isBlank()) {
                String s = "%" + search.toLowerCase() + "%";
                p.setString(i++, s);
                p.setString(i++, s);
            }

            if (mime != null && !mime.isBlank())
                p.setString(i++, mime + "%");

            List<FileEntry> out = new ArrayList<>();
            try (ResultSet r = p.executeQuery()) {
                while (r.next())
                    out.add(new FileEntry(
                            r.getInt(1), r.getString(2), r.getString(3),
                            r.getLong(4), r.getString(5),
                            r.getObject(6, OffsetDateTime.class)
                    ));
            }
            return out;

        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public boolean deleteFile(int fileId) throws SQLException {
        try (Connection c = ds.getConnection();
             PreparedStatement p = c.prepareStatement("DELETE FROM files WHERE file_id = ?")) {
            p.setInt(1, fileId);
            return p.executeUpdate() > 0;
        }
    }

    public List<DirectoryEntry> getAllDirectories() {
        String sql = """
            SELECT d.directory_id, d.path, d.created_at,
                   COUNT(f.file_id)
            FROM directories d
            LEFT JOIN files f USING (directory_id)
            GROUP BY d.directory_id
            ORDER BY d.path
        """;

        try (Connection c = ds.getConnection();
             PreparedStatement p = c.prepareStatement(sql);
             ResultSet r = p.executeQuery()) {

            List<DirectoryEntry> out = new ArrayList<>();
            while (r.next())
                out.add(new DirectoryEntry(
                        r.getInt(1), r.getString(2),
                        r.getObject(3, OffsetDateTime.class),
                        r.getInt(4)
                ));
            return out;

        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public DirectoryEntry getDirectoryById(int id) {
        String sql = """
            SELECT d.directory_id, d.path, d.created_at,
                   COUNT(f.file_id)
            FROM directories d
            LEFT JOIN files f USING (directory_id)
            WHERE d.directory_id = ?
            GROUP BY d.directory_id
        """;

        try (Connection c = ds.getConnection();
             PreparedStatement p = c.prepareStatement(sql)) {

            p.setInt(1, id);
            try (ResultSet r = p.executeQuery()) {
                if (!r.next()) throw new RuntimeException();
                return new DirectoryEntry(
                        r.getInt(1), r.getString(2),
                        r.getObject(3, OffsetDateTime.class),
                        r.getInt(4)
                );
            }

        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public int createDirectory(String path) throws SQLException {
        try (Connection c = ds.getConnection();
             PreparedStatement p = c.prepareStatement("""
                 INSERT INTO directories (path)
                 VALUES (?)
                 ON CONFLICT (path) DO UPDATE SET path = EXCLUDED.path
                 RETURNING directory_id
             """)) {

            p.setString(1, path);
            try (ResultSet r = p.executeQuery()) {
                r.next();
                return r.getInt(1);
            }
        }
    }

    public boolean deleteDirectory(int directoryId) throws SQLException {
        if (directoryId == 1) throw new SQLException();

        try (Connection c = ds.getConnection()) {
            try (PreparedStatement check = c.prepareStatement(
                    "SELECT COUNT(*) FROM files WHERE directory_id = ?")) {
                check.setInt(1, directoryId);
                try (ResultSet r = check.executeQuery()) {
                    r.next();
                    if (r.getInt(1) > 0) throw new SQLException();
                }
            }

            try (PreparedStatement del = c.prepareStatement(
                    "DELETE FROM directories WHERE directory_id = ?")) {
                del.setInt(1, directoryId);
                return del.executeUpdate() > 0;
            }
        }
    }

    public long recordFilePartial(
            String channelId, String messageId, int dirId,
            String partName, int partNumber, long partSize,
            String original, String desc, String mime
    ) throws SQLException {

        String sql = """
            INSERT INTO file_partials
            (disc_channel_id, disc_message_id, directory_id,
             part_name, part_number, part_size,
             original_filename, file_description, mime_type,
             uploaded_via_webhook)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, true)
            RETURNING partial_id
        """;

        try (Connection c = ds.getConnection();
             PreparedStatement p = c.prepareStatement(sql)) {

            p.setString(1, channelId);
            p.setString(2, messageId);
            p.setInt(3, dirId);
            p.setString(4, partName);
            p.setInt(5, partNumber);
            p.setLong(6, partSize);
            p.setString(7, original);
            p.setString(8, desc);
            p.setString(9, mime);

            try (ResultSet r = p.executeQuery()) {
                r.next();
                return r.getLong(1);
            }
        }
    }

    public boolean checkPartialExists(String partName, int dirId) throws SQLException {
        try (Connection c = ds.getConnection();
             PreparedStatement p = c.prepareStatement(
                     "SELECT COUNT(*) FROM file_partials WHERE part_name = ? AND directory_id = ?")) {
            p.setString(1, partName);
            p.setInt(2, dirId);
            try (ResultSet r = p.executeQuery()) {
                r.next();
                return r.getInt(1) > 0;
            }
        }
    }

    public boolean deleteFilePartials(String original, int dirId) throws SQLException {
        try (Connection c = ds.getConnection();
             PreparedStatement p = c.prepareStatement(
                     "DELETE FROM file_partials WHERE original_filename = ? AND directory_id = ?")) {
            p.setString(1, original);
            p.setInt(2, dirId);
            return p.executeUpdate() > 0;
        }
    }

    public List<FilePartialEntry> getFilePartialsByOriginalFilename(String original, int dirId) {
        String sql = """
            SELECT partial_id, disc_channel_id, disc_message_id,
                   part_name, part_number, part_size,
                   original_filename, mime_type,
                   uploaded_via_webhook, created_at
            FROM file_partials
            WHERE original_filename = ? AND directory_id = ?
            ORDER BY part_number
        """;

        try (Connection c = ds.getConnection();
             PreparedStatement p = c.prepareStatement(sql)) {

            p.setString(1, original);
            p.setInt(2, dirId);

            List<FilePartialEntry> out = new ArrayList<>();
            try (ResultSet r = p.executeQuery()) {
                while (r.next())
                    out.add(new FilePartialEntry(
                            r.getLong(1), r.getString(2), r.getString(3),
                            r.getString(4), r.getInt(5), r.getLong(6),
                            r.getString(7), r.getString(8),
                            r.getBoolean(9),
                            r.getObject(10, OffsetDateTime.class)
                    ));
            }
            return out;

        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public List<PartialGroupEntry> getUniqueOriginalFilesFromPartials(int dirId, String search) {
        StringBuilder q = new StringBuilder("""
            SELECT original_filename, mime_type, directory_id,
                   MAX(created_at), SUM(part_size), MAX(file_description)
            FROM file_partials
            WHERE directory_id = ?
        """);

        if (search != null && !search.isBlank())
            q.append(" AND LOWER(original_filename) LIKE ?");

        q.append(" GROUP BY original_filename, mime_type, directory_id ORDER BY original_filename");

        try (Connection c = ds.getConnection();
             PreparedStatement p = c.prepareStatement(q.toString())) {

            int i = 1;
            p.setInt(i++, dirId);
            if (search != null && !search.isBlank())
                p.setString(i++, "%" + search.toLowerCase() + "%");

            List<PartialGroupEntry> out = new ArrayList<>();
            try (ResultSet r = p.executeQuery()) {
                while (r.next())
                    out.add(new PartialGroupEntry(
                            r.getString(1),
                            r.getString(2),
                            r.getInt(3),
                            r.getObject(4, OffsetDateTime.class),
                            r.getLong(5),
                            r.getString(6)
                    ));
            }
            return out;

        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }
}
