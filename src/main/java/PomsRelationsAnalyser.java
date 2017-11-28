import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import org.slf4j.Logger;
import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

import static org.slf4j.LoggerFactory.getLogger;

/**
 * This tool analyse folders to find pom.xml files. It extracts artifact id and parent id to create a tree view of maven projects. 
 */
public class PomsRelationsAnalyser {

    private static final Logger LOGGER = getLogger(PomsRelationsAnalyser.class);

    public static void main(String... args) throws IOException {
        File projectSource;
        if (args.length > 0) {
            projectSource = new File(args[0]);
        } else {
            projectSource = new File(System.getProperty("user.home") + "/IdeaProjects");
        }

        LOGGER.info("Analysing from base path: " + projectSource);

        Set<Project> projects = Files //
                .find(projectSource.toPath(), 10, (p, bfa) -> p.getFileName().toString().equals("pom.xml")) //
                .map(PomsRelationsAnalyser::parsePom) //
                .filter(Objects::nonNull) // Should never be null
                .filter(p -> Objects.nonNull(p.artifactId)) // if pom is incomplete or parsing fails 
                .collect(Collectors.toSet());

        // print all
        try(Writer out = new OutputStreamWriter(System.out)) {
            projects.stream() //
                    .filter(p -> p.parentArtifactId == null) //
                    .map(AppNode::new) //
                    .peek(n -> addChildren(20, n, projects)) //
                    .sorted(Comparator.naturalOrder()) //
                    .peek(n -> n.sort(Comparator.naturalOrder())) //
                    .forEach(n -> printNode(out, "", n));
            out.flush();
        }
    }

    private static <T> void printNode(Writer out, String padding, AppNode<T> node) {
        try {
            out.append(padding).append("+- ").append(node.data.toString()).append('\n');
        } catch (IOException e) {
            e.printStackTrace();
        }
        for (AppNode<T> child : node.children) {
            printNode(out, padding + "  ", child);
        }
    }

    private static void addChildren(int maxDepth, AppNode<Project> root, Collection<Project> projects) {
        if (maxDepth > 0) {
            for (Project project : projects) {
                if (Objects.equals(project.parentArtifactId, root.data.artifactId)
                        && (root.data.groupId == null || Objects.equals(project.parentGroupId, root.data.groupId))) {
                    AppNode<Project> childNode = new AppNode<>(project);
                    addChildren(maxDepth - 1, childNode, projects);
                    root.children.add(childNode);
                }
            }
        }
    }

    // What a screwing API. Maybe using maven API would be best
    private static Project parsePom(Path pomPath) {
        DocumentBuilderFactory documentBuilderFactory = DocumentBuilderFactory.newInstance();
        Project project = new Project();
        project.pomFile = pomPath.toFile();
        try {
            DocumentBuilder documentBuilder = documentBuilderFactory.newDocumentBuilder();
            Document document = documentBuilder.parse(pomPath.toFile());
            NodeList childNodes1 = document.getDocumentElement().getChildNodes();
            for (int i = 0; i < childNodes1.getLength(); i++) {
                Node item = childNodes1.item(i);
                if (item.getNodeName().equals("artifactId")) {
                    project.artifactId = item.getTextContent();
                } else if (item.getNodeName().equals("groupId")) {
                    project.groupId = item.getTextContent();
                }
            }

            Node parentNode = document.getElementsByTagName("parent").item(0);

            if (parentNode != null) {
                NodeList childNodes = parentNode.getChildNodes();
                for (int i = 0; i < childNodes.getLength(); i++) {
                    Node parentSubElement = childNodes.item(i);
                    if (parentSubElement.getNodeName().equals("artifactId")) {
                        project.parentArtifactId = parentSubElement.getTextContent();
                    }  else if (parentSubElement.getNodeName().equals("groupId")) {
                        project.parentGroupId = parentSubElement.getTextContent();
                    }
                }
            }
        } catch (ParserConfigurationException | SAXException | IOException e) {
            LOGGER.warn("SAX parse error.", e);
        }
        return project;
    }

    public static class Project implements Comparable<Project> {

        public File pomFile;
        public String parentArtifactId;
        public String parentGroupId;
        public String groupId;
        public String artifactId;

        @Override
        public String toString() {
            return (groupId == null ? "" : groupId + ":") + artifactId;
//            return artifactId + "\t\t\t\t\t" + pomFile.toString();
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            Project project = (Project) o;
            return Objects.equals(groupId, project.groupId) && Objects.equals(artifactId, project.artifactId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(groupId, artifactId);
        }

        @Override
        public int compareTo(Project o) {
            return Comparator.nullsFirst(Comparator.<String>naturalOrder()).compare(toString(), o.toString());
        }
    }

    public static class AppNode<T> implements Comparable<AppNode<T>> {
        private T data;
        private List<AppNode<T>> children = new ArrayList<>();

        public AppNode(T data) {
            this.data = data;
        }

        public void sort(Comparator<T> comparator) {
            children.sort((o1, o2) -> comparator.compare(o1.data, o2.data));
            for (AppNode<T> child : children) {
                child.sort(comparator);
            }
        }

        @Override
        public int compareTo(AppNode<T> o) {
            if (data instanceof Comparable) {
                return ((Comparable) data).compareTo(o.data);
            }
            return 0;
        }
    }
}