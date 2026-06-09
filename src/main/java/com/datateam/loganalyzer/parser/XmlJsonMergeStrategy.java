package com.datateam.loganalyzer.parser;

public class XmlJsonMergeStrategy implements MultilineMergeStrategy {

    private int jsonDepth = 0;
    private int xmlDepth = 0;
    private boolean inJson = false;
    private boolean inXml = false;

    @Override
    public boolean shouldMerge(String currentLine, String previousLine, StringBuilder currentBuffer) {
        if (currentLine == null || currentLine.trim().isEmpty()) {
            return false;
        }

        String trimmed = currentLine.trim();

        if (currentBuffer.length() == 0) {
            if (trimmed.startsWith("{")) {
                inJson = true;
                inXml = false;
                jsonDepth = countChar(trimmed, '{') - countChar(trimmed, '}');
                return jsonDepth > 0;
            }
            if (trimmed.startsWith("<") && !trimmed.startsWith("<?") && !trimmed.startsWith("</")) {
                inXml = true;
                inJson = false;
                xmlDepth = countOpenXmlTags(trimmed) - countCloseXmlTags(trimmed);
                return xmlDepth > 0;
            }
            return false;
        }

        if (inJson) {
            jsonDepth += countChar(trimmed, '{') - countChar(trimmed, '}');
            return jsonDepth > 0;
        }

        if (inXml) {
            xmlDepth += countOpenXmlTags(trimmed) - countCloseXmlTags(trimmed);
            return xmlDepth > 0;
        }

        return false;
    }

    @Override
    public String merge(String currentLine, StringBuilder currentBuffer) {
        if (currentBuffer.length() > 0) {
            currentBuffer.append("\n");
        }
        currentBuffer.append(currentLine);
        return currentBuffer.toString();
    }

    private int countChar(String str, char c) {
        int count = 0;
        for (int i = 0; i < str.length(); i++) {
            if (str.charAt(i) == c) {
                count++;
            }
        }
        return count;
    }

    private int countOpenXmlTags(String str) {
        int count = 0;
        int i = 0;
        while (i < str.length()) {
            int start = str.indexOf('<', i);
            if (start == -1) break;
            if (start + 1 < str.length() && str.charAt(start + 1) != '/' && str.charAt(start + 1) != '?') {
                int end = str.indexOf('>', start);
                if (end != -1 && str.charAt(end - 1) != '/') {
                    count++;
                }
            }
            i = start + 1;
        }
        return count;
    }

    private int countCloseXmlTags(String str) {
        int count = 0;
        int i = 0;
        while (i < str.length()) {
            int start = str.indexOf("</", i);
            if (start == -1) break;
            count++;
            i = start + 2;
        }
        i = 0;
        while (i < str.length()) {
            int start = str.indexOf("/>", i);
            if (start == -1) break;
            count++;
            i = start + 2;
        }
        return count;
    }

    public void reset() {
        jsonDepth = 0;
        xmlDepth = 0;
        inJson = false;
        inXml = false;
    }

    @Override
    public String getName() {
        return "xml-json";
    }
}
