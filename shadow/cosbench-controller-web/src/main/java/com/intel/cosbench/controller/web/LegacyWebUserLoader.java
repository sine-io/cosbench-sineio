package com.intel.cosbench.controller.web;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import com.intel.cosbench.utils.AuthValidator;

final class LegacyWebUserLoader {

    private static final String USERS_FILE_PROPERTY = "cosbench.web.cosbenchUsers";

    private LegacyWebUserLoader() {
    }

    static Map<String, String> load(String defaultUsername, String defaultPassword, String configuredFile) {
        Map<String, String> users = new LinkedHashMap<>();
        users.put(defaultUsername, defaultPassword);

        try {
            Path usersFile = usersFile(configuredFile);
            if (usersFile == null) {
                return Map.copyOf(users);
            }
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            NodeList userNodes = factory.newDocumentBuilder().parse(usersFile.toFile())
                    .getElementsByTagName("user");
            for (int i = 0; i < userNodes.getLength(); i++) {
                Node node = userNodes.item(i);
                if (node.getNodeType() != Node.ELEMENT_NODE) {
                    continue;
                }
                Element user = (Element) node;
                String username = user.getAttribute("username");
                String password = user.getAttribute("password");
                if (username.isEmpty() || password.isEmpty()) {
                    throw new IllegalArgumentException("user entries require username and password");
                }
                users.put(username, password);
            }
            return Map.copyOf(users);
        } catch (Exception e) {
            throw new IllegalStateException("cannot load Controller Web users: " + e.getMessage(), e);
        }
    }

    private static Path usersFile(String configuredFile) {
        if (configuredFile != null && !configuredFile.isBlank()) {
            Path path = Path.of(configuredFile);
            if (!Files.isRegularFile(path)) {
                throw new IllegalArgumentException("user file does not exist: " + path);
            }
            return path;
        }

        Path path = Path.of(AuthValidator.getConfigFile());
        if (Files.isRegularFile(path)) {
            return path;
        }
        if (System.getProperty(USERS_FILE_PROPERTY) != null) {
            throw new IllegalArgumentException("user file does not exist: " + path);
        }
        return null;
    }
}
