package iped.app.ui;

import java.io.IOException;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import javax.swing.event.TreeExpansionEvent;
import javax.swing.event.TreeExpansionListener;
import javax.swing.event.TreeSelectionEvent;
import javax.swing.event.TreeSelectionListener;
import javax.swing.tree.TreePath;

import com.zaxxer.sparsebits.SparseBitSet;

import iped.engine.search.MultiSearchResult;
import iped.exception.ParseException;
import iped.exception.QueryNodeException;
import iped.search.IMultiSearchResult;
import iped.viewers.api.IFilter;
import iped.viewers.api.IFilterer;
import iped.viewers.api.IResultSetFilter;
import iped.viewers.api.IResultSetFilterer;

public class BookmarksTreeListener implements TreeSelectionListener, TreeExpansionListener, ClearFilterListener, IResultSetFilterer {

    private HashSet<Object> selection = new HashSet<>();
    private volatile boolean updatingSelection = false;
    private long collapsed = 0;
    private boolean clearing = false;

    public Set<String> getSelectedBookmarkNames() {
        return selection.stream().filter(b -> b != BookmarksTreeModel.ROOT && b != BookmarksTreeModel.NO_BOOKMARKS)
                .map(b -> b.toString()).collect(Collectors.toSet());
    }

    public boolean isRootSelected() {
        return selection.contains(BookmarksTreeModel.ROOT);
    }

    public boolean isNoBookmarksSelected() {
        return selection.contains(BookmarksTreeModel.NO_BOOKMARKS);
    }

    @Override
    public void valueChanged(TreeSelectionEvent evt) {

        if (updatingSelection) {
            return;
        }

        if (System.currentTimeMillis() - collapsed < 500) {
            if (evt.getPath().getLastPathComponent().equals(BookmarksTreeModel.ROOT)) {
                updateModelAndSelection();
            }
            return;
        }

        for (TreePath path : evt.getPaths()) {
            if (selection.contains(path.getLastPathComponent())) {
                selection.remove(path.getLastPathComponent());
            } else {
                selection.add(path.getLastPathComponent());
            }
        }

        if (!clearing)
            App.get().appletListener.updateFileListing();

        if (selection.contains(BookmarksTreeModel.ROOT) || selection.isEmpty()) {
            App.get().setBookmarksDefaultColor(true);
        } else {
            App.get().setBookmarksDefaultColor(false);
        }

    }

    public void updateModelAndSelection() {

        updatingSelection = true;
        Set<String> bookmarkSet = ((BookmarksTreeModel) App.get().bookmarksTree.getModel()).bookmarks;

        if (bookmarkSet != null && !selection.isEmpty()) {

            HashSet<Object> tempSel = new HashSet<>(selection);
            selection.clear();

            for (Object path : tempSel) {
                if (path == BookmarksTreeModel.ROOT || path == BookmarksTreeModel.NO_BOOKMARKS
                        || App.get().appCase.getMultiBookmarks().getBookmarkSet().contains(path)) {
                    selection.add(path);
                }
            }

            ArrayList<TreePath> selectedPaths = new ArrayList<TreePath>();
            for (Object name : selection) {
                Object[] path = name == BookmarksTreeModel.ROOT ? new Object[] { BookmarksTreeModel.ROOT }
                        : new Object[] { BookmarksTreeModel.ROOT, name };
                selectedPaths.add(new TreePath(path));
            }

            boolean rootCollapsed = App.get().bookmarksTree.isCollapsed(0);
            App.get().bookmarksTree.setModel(new BookmarksTreeModel());
            if (rootCollapsed) {
                App.get().bookmarksTree.collapseRow(0);
            }

            App.get().bookmarksTree.setSelectionPaths(selectedPaths.toArray(new TreePath[0]));

        } else {
            boolean rootCollapsed = App.get().bookmarksTree.isCollapsed(0);
            App.get().bookmarksTree.setModel(new BookmarksTreeModel());
            if (rootCollapsed) {
                App.get().bookmarksTree.collapseRow(0);
            }
        }
        updatingSelection = false;
    }

    @Override
    public void treeExpanded(TreeExpansionEvent event) {

    }

    @Override
    public void treeCollapsed(TreeExpansionEvent event) {
        collapsed = System.currentTimeMillis();

    }

    @Override
    public void clearFilter() {
        clearing = true;
        App.get().bookmarksTree.clearSelection();
        clearing = false;
    }

    @Override
    public List getDefinedFilters() {
        ArrayList<IFilter> result = new ArrayList<IFilter>();
        BookmarksTreeListener self = this;
        Set<String> bookmarkSelection = getSelectedBookmarkNames();
        if ((!bookmarkSelection.isEmpty() || isNoBookmarksSelected())
                && !isRootSelected()) {
            if ((!bookmarkSelection.isEmpty() || isNoBookmarksSelected())
                    && !isRootSelected()) {
                for (String bookmark : bookmarkSelection) {
                    result.add(new IResultSetFilter() {
                        @Override
                        public IMultiSearchResult filterResult(IMultiSearchResult src)
                                throws ParseException, QueryNodeException, IOException {
                            Set<String> bookmarkSelection = new HashSet<String>();
                            bookmarkSelection.add(bookmark);
                            return (MultiSearchResult) App.get().appCase.getMultiBookmarks().filterBookmarks(src, bookmarkSelection);
                        }
                        public String toString() {
                            return bookmark;
                        }
                    });
                }
                if(isNoBookmarksSelected()) {
                    result.add(new IResultSetFilter() {
                        @Override
                        public IMultiSearchResult filterResult(IMultiSearchResult src)
                                throws ParseException, QueryNodeException, IOException {
                            return (MultiSearchResult) App.get().appCase.getMultiBookmarks().filterNoBookmarks(src);
                        }
                        public String toString() {
                            return "[No Bookmarks]";
                        }
                    });
                }
            }
        }
        return result;
    }

    public String toString() {
        return "Bookmarks filterer";
    }

    @Override
    public Map<Integer, BitSet> getFilteredBitSets(IMultiSearchResult input) {
        Set<String> bookmarkSelection = getSelectedBookmarkNames();
        if ((!bookmarkSelection.isEmpty() || isNoBookmarksSelected())
                && !isRootSelected()) {
            if (isNoBookmarksSelected()) {
                if (bookmarkSelection.isEmpty()) {
                    return App.get().appCase.getMultiBookmarks().filterBookmarksBitsets(input, null, true);
                } else {
                    return App.get().appCase.getMultiBookmarks().filterBookmarksBitsets(input, bookmarkSelection, true);
                }
            } else {
                return App.get().appCase.getMultiBookmarks().filterBookmarksBitsets(input, bookmarkSelection, false);
            }
        }

        return null;
    }

    @Override
    public IFilter getFilter() {
        BookmarksTreeListener self = this;
        Set<String> bookmarkSelection = getSelectedBookmarkNames();
        if ((!bookmarkSelection.isEmpty() || isNoBookmarksSelected())
                && !isRootSelected()) {
            return new IResultSetFilter() {
                @Override
                public IMultiSearchResult filterResult(IMultiSearchResult src)
                        throws ParseException, QueryNodeException, IOException {
                    if (isNoBookmarksSelected()) {
                        if (bookmarkSelection.isEmpty()) {
                            return App.get().appCase.getMultiBookmarks().filterNoBookmarks(src);
                        } else {
                            return App.get().appCase.getMultiBookmarks().filterBookmarksOrNoBookmarks(src, bookmarkSelection);
                        }
                    } else {
                        return App.get().appCase.getMultiBookmarks().filterBookmarks(src, bookmarkSelection);
                    }
                }
            };
        }
        return null;
    }
}
