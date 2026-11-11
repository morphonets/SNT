/*-
 * #%L
 * Fiji distribution of ImageJ for the life sciences.
 * %%
 * Copyright (C) 2010 - 2026 Fiji developers.
 * %%
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public
 * License along with this program.  If not, see
 * <http://www.gnu.org/licenses/gpl-3.0.html>.
 * #L%
 */

package sc.fiji.snt.util;

import net.imagej.updater.FilesCollection;
import net.imagej.updater.UpToDate;
import net.imagej.updater.UpdateSite;
import org.scijava.app.AppService;
import org.xml.sax.SAXException;
import sc.fiji.snt.SNTUtils;

import javax.xml.parsers.ParserConfigurationException;
import java.io.File;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;

/**
 * GUI-free access to the Fiji updater. Callers must treat all methods as
 * potentially failing (e.g., the updater is absent outside Fiji), and
 * catch {@link Throwable}.
 *
 * @author Tiago Ferreira
 */
public final class UpdateChecker {

    private static final String SNT_SITE = "Neuroanatomy";
    private static final int TIMEOUT_MS = 4000;

    private UpdateChecker() {
    }

    /**
     * @return the base directory of the running application, or null if unknown
     */
    public static File baseDirectory() {
        try {
            return SNTUtils.getContext().getService(AppService.class).getApp().getBaseDirectory();
        } catch (final Throwable ignored) {
            return null;
        }
    }

    /**
     * @return the name of the {@link UpToDate.Result} reported by the updater
     */
    public static String upToDateState() throws IOException, ParserConfigurationException, SAXException {
        return UpToDate.check().name();
    }

    /**
     * Compares the timestamp of the Neuroanatomy update site with the local one
     *
     * @param root the base directory of the installation
     * @return true if the site is newer than the local copy, false if it matches it, or null if it could not
     * be determined (e.g., site not active, or offline)
     * @throws Exception if the local updater database could not be read
     */
    public static Boolean isSNTSiteNewer(final File root) throws Exception {
        final FilesCollection files = new FilesCollection(root);
        files.read();
        final UpdateSite site = files.getUpdateSite(SNT_SITE, false);
        if (site == null || !site.isActive()) return null;
        final String url = site.getURL();
        if (url == null || url.isEmpty()) return null;
        final long remote = remoteLastModified(url + (url.endsWith("/") ? "" : "/") + "db.xml.gz");
        return (remote > 0) ? !site.isLastModified(remote) : null;
    }

    /**
     * @return the Last-Modified timestamp of a HEAD request, or -1 on failure
     */
    public static long remoteLastModified(final String url) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) URI.create(url).toURL().openConnection();
            c.setRequestMethod("HEAD");
            c.setUseCaches(false);
            c.setConnectTimeout(TIMEOUT_MS);
            c.setReadTimeout(TIMEOUT_MS);
            return c.getLastModified();
        } catch (final Exception ignored) {
            return -1;
        } finally {
            if (c != null) c.disconnect();
        }
    }
}
