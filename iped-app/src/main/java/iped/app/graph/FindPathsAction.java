package iped.app.graph;

import java.awt.event.ActionEvent;

import javax.swing.AbstractAction;

public class FindPathsAction extends AbstractAction {

    private static final long serialVersionUID = 3030773521363551714L;

    private AppGraphAnalytics app;

    public FindPathsAction(AppGraphAnalytics app) {
        super();
        this.app = app;
    }

    @Override
    public void actionPerformed(ActionEvent e) {
        app.findPathSelected();
    }

}
