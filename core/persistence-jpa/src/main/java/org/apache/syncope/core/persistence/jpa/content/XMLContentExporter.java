/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.syncope.core.persistence.jpa.content;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.JoinTable;
import jakarta.persistence.Table;
import jakarta.persistence.metamodel.Attribute;
import jakarta.persistence.metamodel.EntityType;
import jakarta.persistence.metamodel.PluralAttribute;
import jakarta.xml.bind.DatatypeConverter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.sql.Blob;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.StringJoiner;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Supplier;
import java.util.stream.Stream;
import javax.sql.DataSource;
import javax.xml.XMLConstants;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerConfigurationException;
import javax.xml.transform.sax.SAXTransformerFactory;
import javax.xml.transform.sax.TransformerHandler;
import javax.xml.transform.stream.StreamResult;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.cxf.helpers.IOUtils;
import org.apache.openjpa.lib.util.collections.BidiMap;
import org.apache.openjpa.lib.util.collections.DualHashBidiMap;
import org.apache.syncope.common.lib.SyncopeConstants;
import org.apache.syncope.core.persistence.api.DomainHolder;
import org.apache.syncope.core.persistence.api.content.ContentExporter;
import org.apache.syncope.core.persistence.api.dao.AuditConfDAO;
import org.apache.syncope.core.persistence.api.dao.RealmDAO;
import org.apache.syncope.core.persistence.jpa.entity.JPARealm;
import org.apache.syncope.core.provisioning.api.utils.FormatUtils;
import org.apache.syncope.core.spring.ApplicationContextProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.jdbc.support.JdbcUtils;
import org.springframework.jdbc.support.MetaDataAccessException;
import org.springframework.orm.jpa.EntityManagerFactoryUtils;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.AttributesImpl;

/**
 * Export internal storage content as XML.
 */
public class XMLContentExporter implements ContentExporter {

    protected static final Logger LOG = LoggerFactory.getLogger(XMLContentExporter.class);

    protected static final Set<String> TABLE_PREFIXES_TO_BE_EXCLUDED = Set.of(
            "QRTZ_", AuditConfDAO.AUDIT_ENTRY_TABLE);

    protected static boolean isTableAllowed(final String tableName) {
        return TABLE_PREFIXES_TO_BE_EXCLUDED.stream().
                allMatch(prefix -> !tableName.toUpperCase().startsWith(prefix.toUpperCase()));
    }

    protected static String getValues(final ResultSet rs, final String columnName, final Integer columnType)
            throws SQLException {

        String value = null;

        try {
            switch (columnType) {
                case Types.BINARY:
                case Types.VARBINARY:
                case Types.LONGVARBINARY:
                    InputStream is = rs.getBinaryStream(columnName);
                    if (is != null) {
                        value = DatatypeConverter.printHexBinary(IOUtils.toString(is).getBytes());
                    }
                    break;

                case Types.BLOB:
                    Blob blob = rs.getBlob(columnName);
                    if (blob != null) {
                        value = DatatypeConverter.printHexBinary(IOUtils.toString(blob.getBinaryStream()).getBytes());
                    }
                    break;

                case Types.BIT:
                case Types.BOOLEAN:
                    value = rs.getBoolean(columnName) ? "1" : "0";
                    break;

                case Types.DATE:
                case Types.TIME:
                case Types.TIMESTAMP:
                    Timestamp timestamp = rs.getTimestamp(columnName);
                    if (timestamp != null) {
                        value = FormatUtils.format(OffsetDateTime.ofInstant(
                                Instant.ofEpochMilli(timestamp.getTime()), ZoneId.systemDefault()));
                    }
                    break;

                default:
                    value = rs.getString(columnName);
            }
        } catch (IOException e) {
            LOG.error("Error fetching value from {}", columnName, e);
        }

        return value;
    }

    protected static String columnName(final Supplier<Stream<Attribute<?, ?>>> attrs, final String columnName) {
        String name = attrs.get().map(attr -> {
            if (attr.getName().equalsIgnoreCase(columnName)) {
                return attr.getName();
            }

            Field field = (Field) attr.getJavaMember();
            Column column = field.getAnnotation(Column.class);
            if (column != null && column.name().equalsIgnoreCase(columnName)) {
                return column.name();
            }

            return null;
        }).filter(Objects::nonNull).findFirst().orElse(columnName);

        if (StringUtils.endsWithIgnoreCase(name, "_ID")) {
            String left = StringUtils.substringBefore(name, "_");
            String prefix = attrs.get().filter(attr -> left.equalsIgnoreCase(attr.getName())).findFirst().
                    map(Attribute::getName).orElse(left);
            name = prefix + "_id";
        }

        return name;
    }

    protected static Map<String, Pair<String, String>> relationTables(final BidiMap<String, EntityType<?>> entities) {
        Map<String, Pair<String, String>> relationTables = new HashMap<>();

        entities.values().stream().forEach(e -> e.getAttributes().stream().
                filter(a -> a.getPersistentAttributeType() != Attribute.PersistentAttributeType.BASIC).
                forEach(a -> {
                    Field field = (Field) a.getJavaMember();

                    String attrName = Optional.ofNullable(field.getAnnotation(Column.class)).
                            map(Column::name).
                            orElse(a.getName());

                    Optional.ofNullable(field.getAnnotation(CollectionTable.class)).
                            ifPresent(collectionTable -> relationTables.put(
                            collectionTable.name(),
                            Pair.of(attrName, collectionTable.joinColumns()[0].name())));

                    Optional.ofNullable(field.getAnnotation(JoinTable.class)).ifPresent(joinTable -> {
                        String tableName = joinTable.name();
                        if (StringUtils.isBlank(tableName)) {
                            tableName = entities.getKey(e) + "_"
                                    + entities.getKey(((PluralAttribute) a).getElementType());
                        }

                        relationTables.put(
                                tableName,
                                Pair.of(joinTable.joinColumns()[0].name(),
                                        joinTable.inverseJoinColumns()[0].name()));
                    });
                }));

        return relationTables;
    }

    protected static List<String> sortByForeignKeys(
            final Connection conn, final String schema, final Set<String> tableNames)
            throws SQLException {

        Set<MultiParentNode<String>> roots = new HashSet<>();

        DatabaseMetaData meta = conn.getMetaData();

        Map<String, MultiParentNode<String>> exploited = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        Set<String> pkTableNames = new HashSet<>();

        for (String tableName : tableNames) {
            MultiParentNode<String> node = Optional.ofNullable(exploited.get(tableName)).orElseGet(() -> {
                MultiParentNode<String> n = new MultiParentNode<>(tableName);
                roots.add(n);
                exploited.put(tableName, n);
                return n;
            });

            pkTableNames.clear();
            try (ResultSet rs = meta.getImportedKeys(conn.getCatalog(), schema, tableName)) {
                // this is to avoid repetition
                while (rs.next()) {
                    pkTableNames.add(rs.getString("PKTABLE_NAME"));
                }
            }

            pkTableNames.stream().
                    filter(pkTableName -> !tableName.equalsIgnoreCase(pkTableName)).
                    forEach(pkTableName -> {

                        MultiParentNode<String> pkNode = Optional.ofNullable(exploited.get(pkTableName)).
                                orElseGet(() -> {
                                    MultiParentNode<String> n = new MultiParentNode<>(pkTableName);
                                    roots.add(n);
                                    exploited.put(pkTableName, n);
                                    return n;
                                });

                        pkNode.addChild(node);

                        if (roots.contains(node)) {
                            roots.remove(node);
                        }
                    });
        }

        List<String> sortedTableNames = new ArrayList<>(tableNames.size());
        MultiParentNodeOp.traverseTree(roots, sortedTableNames);

        // remove from sortedTableNames any table possibly added during lookup 
        // but matching some item in this.tablePrefixesToBeExcluded
        sortedTableNames.retainAll(tableNames);

        LOG.debug("Tables after retainAll {}", sortedTableNames);

        Collections.reverse(sortedTableNames);

        return sortedTableNames;
    }

    protected final DomainHolder domainHolder;

    protected final RealmDAO realmDAO;

    public XMLContentExporter(final DomainHolder domainHolder, final RealmDAO realmDAO) {
        this.domainHolder = domainHolder;
        this.realmDAO = realmDAO;
    }

    @SuppressWarnings("unchecked")
    protected void exportTable(
            final DataSource dataSource,
            final String tableName,
            final int threshold,
            final BidiMap<String, EntityType<?>> entities,
            final Map<String, Pair<String, String>> relationTables,
            final TransformerHandler handler) throws SQLException, MetaDataAccessException, SAXException {

        LOG.debug("Export table {}", tableName);

        String orderBy = JdbcUtils.extractDatabaseMetaData(dataSource, meta -> {
            StringJoiner ob = new StringJoiner(",");

            // retrieve primary keys to perform an ordered select
            try (ResultSet pkeyRS = meta.getPrimaryKeys(null, null, tableName)) {
                while (pkeyRS.next()) {
                    Optional.ofNullable(pkeyRS.getString("COLUMN_NAME")).ifPresent(ob::add);
                }
            }

            return ob.toString();
        });

        // ------------------------------------
        StringBuilder query = new StringBuilder();
        query.append("SELECT * FROM ").append(tableName).append(" a");
        if (StringUtils.isNotBlank(orderBy)) {
            query.append(" ORDER BY ").append(orderBy);
        }

        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        jdbcTemplate.setMaxRows(threshold);

        Optional<EntityType<?>> entity = entities.entrySet().stream().
                filter(entry -> entry.getKey().equalsIgnoreCase(tableName)).
                findFirst().
                map(Map.Entry::getValue);

        String outputTableName = entity.map(entities::getKey).
                orElseGet(() -> relationTables.keySet().stream().
                filter(tableName::equalsIgnoreCase).findFirst().
                orElse(tableName));

        List<Map<String, String>> rows = new ArrayList<>();

        jdbcTemplate.query(query.toString(), rs -> {
            Map<String, String> row = new HashMap<>();
            rows.add(row);

            ResultSetMetaData rsMeta = rs.getMetaData();
            for (int i = 0; i < rsMeta.getColumnCount(); i++) {
                String columnName = rsMeta.getColumnName(i + 1);
                Integer columnType = rsMeta.getColumnType(i + 1);

                // Retrieve value taking care of binary values.
                Optional.ofNullable(getValues(rs, columnName, columnType)).ifPresent(value -> {
                    String name = entity.map(e -> columnName(
                            () -> (Stream<Attribute<?, ?>>) e.getAttributes().stream(), columnName)).
                            orElse(columnName);

                    if (relationTables.containsKey(outputTableName)) {
                        Pair<String, String> relationColumns = relationTables.get(outputTableName);
                        if (name.equalsIgnoreCase(relationColumns.getLeft())) {
                            name = relationColumns.getLeft();
                        } else if (name.equalsIgnoreCase(relationColumns.getRight())) {
                            name = relationColumns.getRight();
                        }
                    }

                    row.put(name, value);
                    LOG.debug("Add for table {}: {}=\"{}\"", outputTableName, name, value);
                });
            }
        });

        if (tableName.equalsIgnoreCase(JPARealm.TABLE)) {
            List<Map<String, String>> realmRows = new ArrayList<>(rows);
            rows.clear();
            realmDAO.findDescendants(SyncopeConstants.ROOT_REALM, null, -1, -1).
                    forEach(realm -> realmRows.stream().filter(row -> {

                String id = Optional.ofNullable(row.get("ID")).orElseGet(() -> row.get("id"));
                return realm.getKey().equals(id);
            }).findFirst().ifPresent(rows::add));
        }

        for (Map<String, String> row : rows) {
            AttributesImpl attrs = new AttributesImpl();
            row.forEach((key, value) -> attrs.addAttribute("", "", key, "CDATA", value));

            handler.startElement("", "", outputTableName, attrs);
            handler.endElement("", "", outputTableName);
        }
    }

    @Override
    public void export(
            final String domain,
            final int tableThreshold,
            final OutputStream os)
            throws SAXException, TransformerConfigurationException {

        StreamResult streamResult = new StreamResult(os);
        SAXTransformerFactory transformerFactory = (SAXTransformerFactory) SAXTransformerFactory.newInstance();
        transformerFactory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);

        TransformerHandler handler = transformerFactory.newTransformerHandler();
        Transformer serializer = handler.getTransformer();
        serializer.setOutputProperty(OutputKeys.ENCODING, StandardCharsets.UTF_8.name());
        serializer.setOutputProperty(OutputKeys.INDENT, "yes");
        handler.setResult(streamResult);
        handler.startDocument();
        handler.startElement("", "", ROOT_ELEMENT, new AttributesImpl());

        DataSource dataSource = Optional.ofNullable(domainHolder.getDomains().get(domain)).
                orElseThrow(() -> new IllegalArgumentException("Could not find DataSource for domain " + domain));

        String schema = null;
        if (ApplicationContextProvider.getBeanFactory().containsBean(domain + "DatabaseSchema")) {
            Object schemaBean = ApplicationContextProvider.getBeanFactory().getBean(domain + "DatabaseSchema");
            if (schemaBean instanceof String) {
                schema = (String) schemaBean;
            }
        }

        Connection conn = DataSourceUtils.getConnection(dataSource);
        try (ResultSet rs = conn.getMetaData().
                getTables(null, StringUtils.isBlank(schema) ? null : schema, null, new String[] { "TABLE" })) {

            Set<String> tableNames = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);

            while (rs.next()) {
                String tableName = rs.getString("TABLE_NAME");
                LOG.debug("Found table {}", tableName);
                if (isTableAllowed(tableName)) {
                    tableNames.add(tableName);
                }
            }

            LOG.debug("Tables to be exported {}", tableNames);

            EntityManagerFactory emf = EntityManagerFactoryUtils.findEntityManagerFactory(
                    ApplicationContextProvider.getBeanFactory(), domain);
            Set<EntityType<?>> entityTypes = emf == null ? Set.of() : emf.getMetamodel().getEntities();
            BidiMap<String, EntityType<?>> entities = new DualHashBidiMap<>();
            entityTypes.forEach(entity -> Optional.ofNullable(
                    entity.getBindableJavaType().getAnnotation(Table.class)).
                    ifPresent(table -> entities.put(table.name(), entity)));

            // then sort tables based on foreign keys and dump
            for (String tableName : sortByForeignKeys(conn, schema, tableNames)) {
                try {
                    exportTable(dataSource, tableName, tableThreshold, entities, relationTables(entities), handler);
                } catch (Exception e) {
                    LOG.error("Failure exporting table {}", tableName, e);
                }
            }
        } catch (SQLException e) {
            LOG.error("While exporting database content", e);
        } finally {
            DataSourceUtils.releaseConnection(conn, dataSource);
        }

        handler.endElement("", "", ROOT_ELEMENT);
        handler.endDocument();
    }
}
