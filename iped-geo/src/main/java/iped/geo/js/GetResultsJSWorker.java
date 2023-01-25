package iped.geo.js;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.function.Consumer;

import javax.swing.JProgressBar;
import javax.swing.SortOrder;

import org.apache.commons.lang.StringEscapeUtils;
import org.apache.lucene.document.Document;
import org.apache.tika.metadata.Metadata;

import iped.data.IItemId;
import iped.geo.AbstractMapCanvas;
import iped.geo.kml.KMLResult;
import iped.geo.localization.Messages;
import iped.properties.BasicProps;
import iped.properties.ExtraProperties;
import iped.search.IIPEDSearcher;
import iped.search.IMultiSearchResult;
import iped.utils.SimpleHTMLEncoder;
import iped.viewers.api.IMultiSearchResultProvider;


/**
 * SwingWorker class for parallel creation of leaflet map via javascript data and commands. 
 */
public class GetResultsJSWorker extends iped.viewers.api.CancelableWorker<KMLResult, Integer> {
    IMultiSearchResultProvider app;
    String[] colunas;
    JProgressBar progress;
    int contSemCoordenadas = 0, itemsWithGPS = 0;
    AbstractMapCanvas browserCanvas;
    Consumer consumer;
    private double minlongit;
    private double maxlongit;
    private double minlat;
    private double maxlat;

    /**
     * Constructor.
     * 
     * @param app The provider of the result set that will be rendered on map
     * @param colunas Deprecated. 
     * @param progress The progress bar that will be informed about rendering progress
     * @param browserCanvas The map canvas object where the result will be rendered
     * @param An consumer that will be called after the rendering is done to consume rendering information 
     */
    public GetResultsJSWorker(IMultiSearchResultProvider app, String[] colunas, JProgressBar progress, AbstractMapCanvas browserCanvas, Consumer consumer) {
        this.app = app;
        this.colunas = colunas;
        this.progress = progress;
        this.browserCanvas = browserCanvas;
        this.consumer = consumer;
    }

    /**
     * Implements done swingworker method. Calls the configured consumer passing the result info object.
     */
    @Override
    public void done() {
        if (consumer != null) {
            Object result = null;
            try {
                result = this.get();
            } catch (Exception e) {
                if (e instanceof CancellationException) {

                } else {
                    e.printStackTrace();
                }
            }
            if (result != null) {
                consumer.accept(result);
            }
        }
    }

    /**
     * Implements doInBackground swingworker method. If the canvas is not loaded,
     * creates all markers. If it already loaded only reloads info of markers that must
     * be kept visible according to the result set.
     */
    @Override
    protected KMLResult doInBackground() throws Exception {
        if (browserCanvas.isLoaded()) {
            return doReloadInBackground();
        } else {
            browserCanvas.load();
            return createAllPlacemarks();
        }
    }

    /**
     * Creates javascript markers id array to be passed to the browser canvas
     * with all the markers that must be visible on map.
     */
    protected KMLResult doReloadInBackground() throws Exception {
        int countPlacemark = 0;
        KMLResult kmlResult = new KMLResult();

        int threadCount = 10;
        ExecutorService executorService = Executors.newFixedThreadPool(threadCount);
        try {
            String coluna = null;
            boolean descendingOrder = false;
            try {
                coluna = app.getSortColumn();
                descendingOrder = app.getSortOrder().equals(SortOrder.DESCENDING);
            } catch (Exception ex) {
                coluna = BasicProps.ID;
                descendingOrder = false;
            }

            if (descendingOrder) {
                browserCanvas.setTourOrder(coluna + "-DESC");
            } else {
                browserCanvas.setTourOrder(coluna);
            }

            minlongit = 190.0;
            maxlongit = -190.0;
            minlat = 190.0;
            maxlat = -190.0;

            IMultiSearchResult results = app.getResults();
            Document doc;

            if (progress != null) {
                progress.setMaximum(results.getLength());
            }

            String query = ExtraProperties.LOCATIONS.replace(":", "\\:") + ":*";

            IIPEDSearcher searcher = app.createNewSearch(query);
            IMultiSearchResult multiResult = searcher.multiSearch();

            Map<IItemId, List<Integer>> gpsItems = new HashMap<>();
            for (IItemId item : multiResult.getIterator()) {
                gpsItems.put(item, null);
            }

            List<StringBuffer> gidsList = Collections.synchronizedList(new ArrayList<>());

            StringBuffer gids = null;

            int batchSize = 1000;
            Semaphore sem = new Semaphore(batchSize);
            int maporder = 0;

            for (int row = 0; row < results.getLength(); row++) {
                if (isCancelled()) {
                    return null;
                }

                int finalRow = row;

                if (row % batchSize == 0) {
                    if (gids != null) {
                        sem.acquire(batchSize);
                        sem.release(batchSize);
                        gids.append("]");
                        gidsList.add(gids);
                    }
                    sem.acquire(Math.min(batchSize, results.getLength() - (((int) row / batchSize) * batchSize)));
                    gids = new StringBuffer();
                    gids.append("[");
                }

                if (progress != null) {
                    progress.setValue(finalRow + 1);
                }

                final StringBuffer finalGids = gids;
                final IItemId item = results.getItem(app.getResultsTable().convertRowIndexToModel(finalRow));
                if (!gpsItems.containsKey(item)) {
                    sem.release();
                    continue;
                }
                final int finalMapOrder = maporder; 
                maporder++;

                Runnable r = new Runnable() {
                    @Override
                    public void run() {
                        try {

                            int luceneId = app.getIPEDSource().getLuceneId(item);
                            Document doc = app.getIPEDSource().getSearcher().doc(luceneId);

                            String[] locations = doc.getValues(ExtraProperties.LOCATIONS);

                            if (locations != null && locations.length == 1) {
                                String[] locs = locations[0].split(";"); //$NON-NLS-1$
                                String lat = locs[0].trim();
                                String longit = locs[1].trim();

                                updateViewableRegion(longit, lat);

                                String gid = item.getSourceId() + "_" + item.getId(); //$NON-NLS-1$ //$NON-NLS-2$

                                gpsItems.put(item, null);

                                int checked = 0;
                                if (app.getIPEDSource().getMultiBookmarks().isChecked(item)) {
                                    checked = 1;
                                }
                                ;
                                itemsWithGPS++;
                                finalGids.append("['" + gid + "'," + finalMapOrder + "," + checked + "],");
                            } else {
                                int subitem = -1;
                                List<Integer> subitems = new ArrayList<>();
                                gpsItems.put(item, subitems);
                                for (String location : locations) {
                                    String[] locs = location.split(";"); //$NON-NLS-1$
                                    String lat = locs[0].trim();
                                    String longit = locs[1].trim();

                                    updateViewableRegion(longit, lat);

                                    String gid = "marker_" + item.getSourceId() + "_" + item.getId() + "_" + subitem; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

                                    subitems.add(subitem);

                                    itemsWithGPS++;
                                    finalGids.append("['" + gid + "'," + finalMapOrder + "],");
                                }
                            }

                            if (progress != null)
                                progress.setString(Messages.getString("KMLResult.LoadingGPSData") + ": " + (itemsWithGPS)); //$NON-NLS-1$ //$NON-NLS-2$

                        } catch (Exception e) {
                            e.printStackTrace();
                        } finally {
                            sem.release();
                        }
                    }
                };

                countPlacemark++;

                executorService.execute(r);
            }
            sem.acquire(batchSize);
            sem.release(batchSize);
            if (gids != null && gids.length() > 5) {
                gids.append("]");
                gidsList.add(gids);
            }
            browserCanvas.updateView(gidsList);
            kmlResult.setResultKML("", itemsWithGPS, gpsItems);
            browserCanvas.viewAll(minlongit, minlat, maxlongit, maxlat);
        } catch (Exception e) {
            if (!isCancelled()) {
                e.printStackTrace();
            }
        } finally {
            executorService.shutdown();
        }

        return kmlResult;

    }

    /**
     * Updates internal values that keeps information about
     * the bounding rectangle that shows all items with data of
     * a new marker to be shown.
     */
    synchronized private void updateViewableRegion(String longit, String lat) {
        try {
            double dlongit = Double.parseDouble(longit);
            if (dlongit < minlongit) {
                minlongit = dlongit;
            }
            if (dlongit > maxlongit) {
                maxlongit = dlongit;
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        try {
            double dlat = Double.parseDouble(lat);
            if (dlat < minlat) {
                minlat = dlat;
            }
            if (dlat > maxlat) {
                maxlat = dlat;
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Deprecated
    public static String getBaseGID(String gid) {
        if (gid.split("_").length == 4) {
            return gid.substring(0, gid.lastIndexOf('_'));
        } else {
            return gid;
        }
    }

    @Deprecated
    static public String htmlFormat(String html) {
        if (html == null) {
            return ""; //$NON-NLS-1$
        }
        return SimpleHTMLEncoder.htmlEncode(html);
    }

    @Deprecated
    static public String resolveAltitude(Document doc) {
        String alt = doc.get(ExtraProperties.COMMON_META_PREFIX + Metadata.ALTITUDE.getName());
        return alt;
    }

    /**
     * Creates javascript array with info to create all the placemarks on map canvas.
     */
    protected KMLResult createAllPlacemarks() throws Exception {
        int countPlacemark = 0;
        KMLResult kmlResult = new KMLResult();

        int threadCount = 10;
        ExecutorService executorService = Executors.newFixedThreadPool(threadCount);
        try {

            String coluna = null;
            boolean descendingOrder = false;
            try {
                coluna = app.getSortColumn();
                descendingOrder = app.getSortOrder().equals(SortOrder.DESCENDING);
            } catch (Exception ex) {
                coluna = BasicProps.ID;
                descendingOrder = false;
            }

            if (descendingOrder) {
                browserCanvas.setTourOrder(coluna + "-DESC");
            } else {
                browserCanvas.setTourOrder(coluna);
            }

            minlongit = 190.0;
            maxlongit = -190.0;
            minlat = 190.0;
            maxlat = -190.0;

            Document doc;

            String query = ExtraProperties.LOCATIONS.replace(":", "\\:") + ":*";

            IIPEDSearcher searcher = app.createNewSearch(query);
            IMultiSearchResult multiResult = searcher.multiSearch();

            IMultiSearchResult results = multiResult;
            if (progress != null) {
                progress.setMaximum(results.getLength());
            }

            Map<IItemId, List<Integer>> gpsItems = new HashMap<>();

            SimpleDateFormat df = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss"); //$NON-NLS-1$
            df.setTimeZone(TimeZone.getTimeZone("GMT")); //$NON-NLS-1$

            List<StringBuffer> gidsList = Collections.synchronizedList(new ArrayList<>());
            StringBuffer gids = null;

            int batchSize = 1000;
            Semaphore sem = new Semaphore(batchSize);

            for (int row = 0; row < results.getLength(); row++) {
                if (isCancelled()) {
                    return null;
                }

                int finalRow = row;

                if (row % batchSize == 0) {
                    if (gids != null) {
                        sem.acquire(batchSize);
                        sem.release(batchSize);
                        gids.append("]");
                        gidsList.add(gids);
                    }
                    sem.acquire(Math.min(batchSize, results.getLength() - (((int) row / batchSize) * batchSize)));
                    gids = new StringBuffer();
                    gids.append("[");
                }

                final StringBuffer finalGids = gids;

                Runnable r = new Runnable() {
                    @Override
                    public void run() {
                        try {
                            if (progress != null) {
                                progress.setValue(finalRow + 1);
                            }

                            IItemId item = results.getItem(finalRow);

                            int luceneId = app.getIPEDSource().getLuceneId(item);
                            Document doc = app.getIPEDSource().getSearcher().doc(luceneId);

                            String lat;
                            String longit;
                            String alt = resolveAltitude(doc);

                            String[] locations = doc.getValues(ExtraProperties.LOCATIONS);

                            if (locations != null && locations.length == 1) {
                                String[] locs = locations[0].split(";"); //$NON-NLS-1$
                                lat = locs[0].trim();
                                longit = locs[1].trim();

                                String gid = item.getSourceId() + "_" + item.getId(); //$NON-NLS-1$ //$NON-NLS-2$

                                boolean checked = app.getIPEDSource().getMultiBookmarks().isChecked(item);
                                boolean selected = app.getResultsTable().isRowSelected(finalRow);

                                finalGids.append("['" + gid + "'," + finalRow + ",'" + StringEscapeUtils.escapeJavaScript(htmlFormat(doc.get(BasicProps.NAME))) + "','" + Messages.getString("KMLResult.SearchResultsDescription") + "'," + lat
                                        + "," + longit + "," + checked + "," + selected + "],");

                                updateViewableRegion(longit, lat);
                                itemsWithGPS++;
                                gpsItems.put(item, null);

                            } else if (locations != null && locations.length > 1) {
                                int subitem = -1;
                                List<Integer> subitems = new ArrayList<>();
                                gpsItems.put(item, subitems);
                                for (String location : locations) {
                                    String[] locs = location.split(";"); //$NON-NLS-1$
                                    lat = locs[0].trim();
                                    longit = locs[1].trim();

                                    String gid = item.getSourceId() + "_" + item.getId() + "_" + subitem; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

                                    boolean checked = app.getIPEDSource().getMultiBookmarks().isChecked(item);
                                    boolean selected = app.getResultsTable().isRowSelected(finalRow);

                                    finalGids.append("['" + gid + "'," + finalRow + ",'" + StringEscapeUtils.escapeJavaScript(htmlFormat(doc.get(BasicProps.NAME))) + "','" + Messages.getString("KMLResult.SearchResultsDescription") + "',"
                                            + lat + "," + longit + "," + checked + "," + selected + "],");

                                    updateViewableRegion(longit, lat);
                                    itemsWithGPS++;
                                    subitems.add(subitem);
                                }
                            } else {
                                contSemCoordenadas++;
                            }
                        } catch (Exception e) {
                            e.printStackTrace();
                        } finally {
                            sem.release();
                        }
                    }
                };
                r.run();
                countPlacemark++;
            }
            sem.acquire(batchSize);
            sem.release(batchSize);
            if (gids != null && gids.length() > 5) {
                gids.append("]");
                gidsList.add(gids);
            }

            browserCanvas.createPlacemarks(gidsList);
            browserCanvas.viewAll(minlongit, minlat, maxlongit, maxlat);
            browserCanvas.setLoaded(true);
            kmlResult.setResultKML("", itemsWithGPS, gpsItems);
        } catch (Exception e) {
            if (!isCancelled()) {
                e.printStackTrace();
            }
        } finally {
            executorService.shutdown();
        }

        return kmlResult;
    }

}
