/*
 * Copyright 2012 Sebastian Annies, Hamburg
 *
 * Licensed under the Apache License, Version 2.0 (the License);
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an AS IS BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.googlecode.mp4parser.authoring.adaptivestreaming;

import com.coremedia.iso.boxes.Box;
import com.coremedia.iso.boxes.Container;
import com.coremedia.iso.boxes.SoundMediaHeaderBox;
import com.coremedia.iso.boxes.VideoMediaHeaderBox;
import com.coremedia.iso.boxes.fragment.MovieFragmentBox;
import com.googlecode.mp4parser.authoring.Movie;
import com.googlecode.mp4parser.authoring.Track;
import com.googlecode.mp4parser.authoring.builder.DefaultMp4Builder;
import com.googlecode.mp4parser.authoring.builder.FragmentIntersectionFinder;
import com.googlecode.mp4parser.authoring.builder.FragmentedMp4Builder;
import com.googlecode.mp4parser.authoring.builder.SyncSampleIntersectFinderImpl;
import com.googlecode.mp4parser.authoring.tracks.ChangeTimeScaleTrack;

import java.io.File;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.lang.Long;import java.lang.String;import java.lang.System;import java.nio.channels.FileChannel;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.logging.Logger;

public class FlatPackageWriterImpl implements PackageWriter {
    private static Logger LOG = Logger.getLogger(FlatPackageWriterImpl.class.getName());
    long timeScale = 10000000;

    private File outputDirectory;
    private boolean debugOutput;
    private FragmentedMp4Builder ismvBuilder;
    ManifestWriter manifestWriter;

    public void setIntersectionFinder(FragmentIntersectionFinder intersectionFinder) {
        this.intersectionFinder = intersectionFinder;
    }

    FragmentIntersectionFinder intersectionFinder;

    /**
     * Creates a factory for a smooth streaming package. A smooth streaming package is
     * a collection of files that can be served by a webserver as a smooth streaming
     * stream.
     */
    public FlatPackageWriterImpl(int minFragmentDuration) {

    }

    public void setOutputDirectory(File outputDirectory) {
        assert outputDirectory.isDirectory();
        this.outputDirectory = outputDirectory;

    }

    public void setDebugOutput(boolean debugOutput) {
        this.debugOutput = debugOutput;
    }

    public void setIsmvBuilder(FragmentedMp4Builder ismvBuilder) {
        this.ismvBuilder = ismvBuilder;
        this.manifestWriter = new FlatManifestWriterImpl(ismvBuilder.getFragmentIntersectionFinder());
    }

    public void setManifestWriter(ManifestWriter manifestWriter) {
        this.manifestWriter = manifestWriter;
    }

    /**
     * Writes the movie given as <code>qualities</code> flattened into the
     * <code>outputDirectory</code>.
     *
     * @param source the source movie with all qualities
     * @throws IOException in case file I/O fails
     */
    public void write(Movie source) throws IOException {
        if (intersectionFinder == null) {
            Track refTrack = null;
            for (Track track : source.getTracks()) {
                if (track.getHandler().equals("vide")) {
                    refTrack = track;
                    break;
                }
            }
            intersectionFinder = new SyncSampleIntersectFinderImpl(source, refTrack, -1);
        }
        if (ismvBuilder == null) {
            ismvBuilder = new FragmentedMp4Builder();
        }
        ismvBuilder.setIntersectionFinder(intersectionFinder);
        manifestWriter = new FlatManifestWriterImpl(intersectionFinder);

        if (debugOutput) {
            outputDirectory.mkdirs();
            DefaultMp4Builder defaultMp4Builder = new DefaultMp4Builder();
            Container muxed = defaultMp4Builder.build(source);
            File muxedFile = new File(outputDirectory, "debug_1_muxed.mp4");
            FileOutputStream muxedFileOutputStream = new FileOutputStream(muxedFile);
            muxed.writeContainer(muxedFileOutputStream.getChannel());
            muxedFileOutputStream.close();
        }
        Movie cleanedSource = removeUnknownTracks(source);
        Movie movieWithAdjustedTimescale = correctTimescale(cleanedSource);

        if (debugOutput) {
            DefaultMp4Builder defaultMp4Builder = new DefaultMp4Builder();
            Container muxed = defaultMp4Builder.build(movieWithAdjustedTimescale);
            File muxedFile = new File(outputDirectory, "debug_2_timescale.mp4");
            FileOutputStream muxedFileOutputStream = new FileOutputStream(muxedFile);
            muxed.writeContainer(muxedFileOutputStream.getChannel());
            muxedFileOutputStream.close();
        }
        Container isoFile = ismvBuilder.build(movieWithAdjustedTimescale);
        if (debugOutput) {
            File allQualities = new File(outputDirectory, "debug_3_fragmented.mp4");
            FileOutputStream allQualis = new FileOutputStream(allQualities);
            isoFile.writeContainer(allQualis.getChannel());

            allQualis.close();
        }


        for (Track track : movieWithAdjustedTimescale.getTracks()) {
            String bitrate = Long.toString(manifestWriter.getBitrate(track));
            long trackId = track.getTrackMetaData().getTrackId();
            Iterator<Box> boxIt = isoFile.getBoxes().iterator();
            File mediaOutDir;
            if (track.getMediaHeaderBox() instanceof SoundMediaHeaderBox) {
                mediaOutDir = new File(outputDirectory, "audio");

            } else if (track.getMediaHeaderBox() instanceof VideoMediaHeaderBox) {
                mediaOutDir = new File(outputDirectory, "video");
            } else {
                System.err.println("Skipping Track with handler " + track.getHandler() + " and " + track.getMediaHeaderBox().getClass().getSimpleName());
                continue;
            }
            File bitRateOutputDir = new File(mediaOutDir, bitrate);
            bitRateOutputDir.mkdirs();
            LOG.finer("Created : " + bitRateOutputDir.getCanonicalPath());

            long[] fragmentTimes = manifestWriter.calculateFragmentDurations(track, movieWithAdjustedTimescale);
            long startTime = 0;
            int currentFragment = 0;
            while (boxIt.hasNext()) {
                Box b = boxIt.next();
                if (b instanceof MovieFragmentBox) {
                    assert ((MovieFragmentBox) b).getTrackCount() == 1;
                    if (((MovieFragmentBox) b).getTrackNumbers()[0] == trackId) {
                        FileOutputStream fos = new FileOutputStream(new File(bitRateOutputDir, Long.toString(startTime)));
                        startTime += fragmentTimes[currentFragment++];
                        FileChannel fc = fos.getChannel();

                        Box mdat = boxIt.next();
                        assert mdat.getType().equals("mdat");
                        b.getBox(fc); // moof
                        mdat.getBox(fc); // mdat
                        fc.truncate(fc.position());
                        fc.close();
                    }
                }

            }
        }
        FileWriter fw = new FileWriter(new File(outputDirectory, "Manifest"));
        fw.write(manifestWriter.getManifest(movieWithAdjustedTimescale));
        fw.close();

    }

    private Movie removeUnknownTracks(Movie source) {
        List<Track> tracks = new LinkedList<Track>();
        for (Track track : source.getTracks()) {
            if ("vide".equals(track.getHandler()) || "soun".equals(track.getHandler())) {
                tracks.add(track);
            } else {
                LOG.fine("Removed track " + track);
            }
        }
        source.setTracks(tracks);
        return source;
    }


    /**
     * Returns a new <code>Movie</code> in that all tracks have the timescale 10000000. CTS &amp; DTS are modified
     * in a way that even with more than one framerate the fragments exactly begin at the same time.
     *
     * @param movie original movie
     * @return a movie with timescales suitable for smooth streaming manifests
     */
    public Movie correctTimescale(Movie movie) {
        List<Track> tracks = new LinkedList<Track>();
        for (Track track : movie.getTracks()) {
            tracks.add(new ChangeTimeScaleTrack(track, timeScale, ismvBuilder.getFragmentIntersectionFinder().sampleNumbers(track)));
        }
        movie.setTracks(tracks);
        return movie;

    }

}
