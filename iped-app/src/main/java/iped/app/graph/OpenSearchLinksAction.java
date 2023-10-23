package iped.app.graph;

import java.awt.event.ActionEvent;

import javax.swing.AbstractAction;

public class OpenSearchLinksAction extends AbstractAction {

    private static final long serialVersionUID = -902980467370504606L;

    private AppGraphAnalytics app;

    public OpenSearchLinksAction(AppGraphAnalytics app) {
        super();
        this.app = app;
    }

    @Override
    public void actionPerformed(ActionEvent e) {
        SearchLinksDialog dialog = new SearchLinksDialog(app);
        dialog.setVisible(true);
    }

}
