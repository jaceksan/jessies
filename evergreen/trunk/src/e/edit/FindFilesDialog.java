package e.edit;

import java.awt.event.*;
import java.io.*;
import java.util.List;
import java.util.regex.*;
import javax.swing.*;
import e.forms.*;
import e.gui.*;
import e.util.*;

/**
 * 
 */
public class FindFilesDialog {
    private CheapMonitoredField patternField = new CheapMonitoredField();
    private CheapMonitoredField directoryField = new CheapMonitoredField();
    private JLabel status = new JLabel(" ");
    private JList matchList;
    
    private boolean haveSearched;
    private PatternSyntaxException patternSyntaxException;
    
    private Workspace workspace;
    
    private FileFinder workerThread;
    
    public class MatchingFile {
        private String name;
        private int matchCount;
        private String regularExpression;
        private boolean containsDefinition;
        
        /**
         * For matches based just on filename.
         */
        public MatchingFile(String name) {
            this(name, 0, null, false);
        }
        
        /**
         * For matches based on filename and a regular expression.
         */
        public MatchingFile(String name, int matchCount, String regularExpression, boolean containsDefinition) {
            this.name = name;
            this.matchCount = matchCount;
            this.regularExpression = regularExpression;
            this.containsDefinition = containsDefinition;
        }
        
        public void open() {
            EWindow window = Edit.openFile(workspace.getRootDirectory() + File.separator + name);
            if (window instanceof ETextWindow && regularExpression != null) {
                ETextWindow textWindow = (ETextWindow) window;
                FindAction.INSTANCE.findInText(textWindow, regularExpression);
                textWindow.findNext();
            }
        }
        
        public String toString() {
            StringBuffer result = new StringBuffer(name);
            if (matchCount != 0) {
                result.append(" (");
                result.append(matchCount);
                result.append(matchCount == 1 ? " match" : " matches");
                if (containsDefinition) {
                    result.append(" including definition");
                }
                result.append(")");
            }
            return result.toString();
        }
    }
    
    public class FileFinder extends SwingWorker {
        private DefaultListModel matchModel;
        private String regex;
        private String directory;
        private volatile boolean resultNoLongerWanted;
        
        public synchronized void doFindInDirectory(String pattern, String directory) {
            System.err.println("---doFindInDirectory " + pattern + "...");
            this.matchModel = new DefaultListModel();
            this.regex = pattern;
            this.directory = directory;
            this.resultNoLongerWanted = false;
            
            matchList.setModel(matchModel);
            status.setText("Searching...");
            start();
        }
        
        public synchronized void giveUp() {
            resultNoLongerWanted = true;
        }
        
        public Object construct() {
            System.err.println("---Starting search for " + regex + " in files matching " + directory + "...");
            Thread.currentThread().setName("Search for '" + regex + "' in " + directory);
            try {
                Pattern pattern = Pattern.compile(regex);
                List fileList = workspace.getListOfFilesMatching(directory);
                FileSearcher fileSearcher = new FileSearcher(pattern);
                String root = workspace.getRootDirectory();
                long startTime = System.currentTimeMillis();
                for (int i = 0; i < fileList.size(); i++) {
                    if (resultNoLongerWanted) {
                        System.err.println("---Aborting search!");
                        return null;
                    }
                    try {
                        String candidate = (String) fileList.get(i);
                        if (regex.length() != 0) {
                            int matchCount = fileSearcher.searchFile(root, candidate);
                            if (matchCount > 0) {
                                DefinitionFinder definitionFinder = new DefinitionFinder(FileUtilities.fileFromParentAndString(root, candidate), regex);
                                matchModel.addElement(new MatchingFile(candidate, matchCount, regex, definitionFinder.foundDefinition));
                            }
                        } else {
                            matchModel.addElement(new MatchingFile(candidate));
                        }
                    } catch (FileNotFoundException ex) {
                        ex = ex; // Not our problem.
                    }
                }
                long endTime = System.currentTimeMillis();
                System.err.println("----------took: " + (endTime - startTime) + " ms.");
            } catch (PatternSyntaxException ex) {
                patternSyntaxException = ex;
            } catch (Exception ex) {
                ex.printStackTrace();
            }
            return matchModel;
        }
        
        public void finished() {
            Object result = getValue();
            workerHasFinished();
            if (resultNoLongerWanted) {
                return;
            }
            
            // This means the user can just hit Return if there's only one match.
            if (matchModel.getSize() == 1) {
                matchList.setSelectedIndex(0);
            }
            status.setText(" ");
        }
    }
    
    public static class DefinitionFinder implements TagReader.TagListener {
        public boolean foundDefinition = false;
        private Pattern pattern;
        public DefinitionFinder(File file, String regularExpression) {
            this.pattern = Pattern.compile(regularExpression);
            new TagReader(file, null, this);
        }
        public void tagFound(TagReader.Tag tag) {
            // The tag type test is a hack. In C++ mode, 'p' means function
            // prototype, which isn't interesting. In Java, it means package,
            // which probably isn't interesting. It also means "paragraph",
            // "port", "procedure", "program" and "property", thanks to ectags'
            // liberal re-use of type tags. Pascal, Sql and Tcl programmers
            // beware!
            // The likely best work-around for this would be to submit a
            // patch to ectags so that it at least records in its tags file
            // which mode it was in.
            if (tag.type != 'p' && pattern.matcher(tag.identifier).find()) {
                foundDefinition = true;
            }
        }
        public void taggingFailed(Exception ex) {
            Log.warn("Failed to use tags to check for a definition.", ex);
        }
    }
    
    public class CheapMonitoredField extends EMonitoredTextField {
        public CheapMonitoredField() {
            super(40);
        }
        
        /** Tracks (cheaply) *every* keypress, because it's important to know whether the search results are up-to-date. */
        public void textChanged() {
            super.textChanged();
            haveSearched = false;
        }
        
        public void timerExpired() {
            haveSearched = true;
            showMatches();
        }
    }
    
    public synchronized void showMatches() {
        if (workerThread != null) {
            workerThread.giveUp();
        }
        
        FileFinder newWorkerThread = new FileFinder();
        newWorkerThread.doFindInDirectory(patternField.getText(), directoryField.getText());
        workerThread = newWorkerThread;
    }
    
    public synchronized void workerHasFinished() {
        workerThread = null;
    }
    
    public void initMatchList() {
        if (matchList != null) {
            return;
        }

        matchList = new JList();
        matchList.setCellRenderer(new EListCellRenderer(true));
        matchList.addMouseListener(new MouseAdapter() {
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) {
                    int index = matchList.locationToIndex(e.getPoint());
                    MatchingFile match = (MatchingFile) matchList.getModel().getElementAt(index);
                    match.open();
                }
            }
        });
    }
    
    public FindFilesDialog(Workspace workspace) {
        this.workspace = workspace;
        this.haveSearched = false;
    }

    /**
     * Sets the contents of the text field.
     * The value null causes the pattern to stay as it was.
     */
    public void setPattern(String pattern) {
        if (pattern == null) {
            return;
        }
        patternField.setText(pattern);
    }
    
    public void setFilenamePattern(String pattern) {
        directoryField.setText(pattern);
    }
    
    public void showDialog() {
        if (workspace.isFileListUnsuitableFor("Find Files")) {
            return;
        }
        
        initMatchList();
        
        FormPanel formPanel = new FormPanel();
        formPanel.addRow("Files Containing:", patternField);
        formPanel.addRow("Whose Names Match:", directoryField);
        formPanel.addRow("Matches:", new JScrollPane(matchList));
        formPanel.addRow("", status);
        
        ActionListener listener = new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                openSelectedFilesFromList();
            }
        };
        FormDialog.showNonModal(Edit.getFrame(), "Find Files", formPanel, "Open", listener);
    }
    
    public void openSelectedFilesFromList() {
        ListModel list = matchList.getModel();
        int[] indices = matchList.getSelectedIndices();
        for (int i = 0; i < indices.length; i++) {
            MatchingFile file = (MatchingFile) list.getElementAt(indices[i]);
            file.open();
        }
    }
}
