package iped.app.ui;

import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.util.ArrayList;

import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;

import iped.engine.search.IPEDSearcher;
import iped.engine.util.Util;
import iped.viewers.api.CancelableWorker;
import iped.viewers.util.ProgressDialog;
import iped.engine.search.MultiSearchResult;
import iped.data.IItemId;

public class KeywordListImporter extends CancelableWorker {

    ProgressDialog progress;
    ArrayList<String> keywords, result = new ArrayList<String>(), errors = new ArrayList<String>();
    boolean addBookMarkList = false;
    boolean addBookMarkWords = false;
    String fileName = "";

    public KeywordListImporter(File file, boolean addBookMarkList, boolean addBookMarkWords) {
        this.addBookMarkList = addBookMarkList;
        this.addBookMarkWords = addBookMarkWords;
        try {
            keywords = Util.loadKeywords(file.getAbsolutePath(), Charset.defaultCharset().displayName());
            fileName = file.getName().trim();
            progress = new ProgressDialog(App.get(), this, false);
            progress.setMaximum(keywords.size());

        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    @Override
    protected Object doInBackground() {

        int i = 0;
        for (String keyword : keywords) {
            if (this.isCancelled()) {
                break;
            }

            try {
                IPEDSearcher task = new IPEDSearcher(App.get().appCase, keyword);
                MultiSearchResult searchResults = task.multiSearch();
                if (searchResults.getLength() > 0) {
                    result.add(keyword);
                    
                    if (addBookMarkList || addBookMarkWords){
                    
                        ArrayList<IItemId> uniqueSelectedIds = new ArrayList<IItemId>();
                                    
                        for (IItemId item : searchResults.getIterator()) {
                            uniqueSelectedIds.add(item);
                        }                

                        if (addBookMarkList)
                            App.get().appCase.getMultiBookmarks().addBookmark(uniqueSelectedIds, fileName);                            
                        if (addBookMarkWords)
                            App.get().appCase.getMultiBookmarks().addBookmark(uniqueSelectedIds, keyword);                            
                        uniqueSelectedIds.clear();
                        uniqueSelectedIds = null;
                    }                    
                    
                }
                searchResults = null;
                task = null;

                final int j = ++i;
                SwingUtilities.invokeLater(new Runnable() {
                    public void run() {
                        progress.setProgress(j);
                    }
                });

            } catch (Exception e) {
                e.printStackTrace();
                errors.add(keyword);
            }

        }

        return null;
    }

    @Override
    public void done() {

        progress.close();

        for (String word : result)
            App.get().appCase.getMultiBookmarks().addToTypedWords(word);

        App.get().appCase.getMultiBookmarks().saveState();

        BookmarksController.get().updateUIandHistory();
        BookmarksManager.get().updateList();        

        if (errors.size() > 0) {
            StringBuilder errorTerms = new StringBuilder();
            for (String s : errors) {
                errorTerms.append("\n" + s); //$NON-NLS-1$
            }
            JOptionPane.showMessageDialog(null, Messages.getString("KeywordListImporter.SyntaxError") + errorTerms); //$NON-NLS-1$
        }

        JOptionPane.showMessageDialog(null, Messages.getString("KeywordListImporter.Msg.1") + result.size() //$NON-NLS-1$
                + Messages.getString("KeywordListImporter.Msg.2") + keywords.size()); //$NON-NLS-1$
    }

}
