package com.joshschriever.livenotes.musicxml;

import android.util.SparseArray;

import java.util.List;

import java8.util.Optional;
import java8.util.Spliterator;
import java8.util.Spliterators.AbstractSpliterator;
import java8.util.function.Consumer;
import java8.util.function.Predicate;
import java8.util.stream.Stream;
import nu.xom.Attribute;
import nu.xom.DocType;
import nu.xom.Document;
import nu.xom.Element;
import nu.xom.Elements;

import static java8.util.Comparators.comparingInt;
import static java8.util.function.Predicates.negate;
import static java8.util.stream.Collectors.toList;
import static java8.util.stream.StreamSupport.stream;

// Based on JFugue
public class MusicXmlRenderer implements SimpleParserListener {

    private static final SparseArray<String> BEAT_UNIT_STRINGS = new SparseArray<>(3);

    static {
        BEAT_UNIT_STRINGS.put(2, "half");
        BEAT_UNIT_STRINGS.put(4, "quarter");
        BEAT_UNIT_STRINGS.put(8, "eighth");
    }

    private Document document;
    private Element elRoot;
    private Element elPart;
    private Element elCurMeasure;

    private final DurationHandler durationHandler;
    private final long margin;
    private final KeySigHandler keySigHandler;

    public MusicXmlRenderer(DurationHandler durationHandler, KeySigHandler keySigHandler) {
        this.durationHandler = durationHandler;
        margin = durationHandler.shortestNoteLengthInMillis();
        this.keySigHandler = keySigHandler;

        elRoot = new Element("score-partwise");
        Element elID = new Element("identification");
        Element elCreator = new Element("creator");
        elCreator.addAttribute(new Attribute("type", "software"));
        elCreator.appendChild("Live Notes");
        elID.appendChild(elCreator);
        elRoot.appendChild(elID);

        document = new Document(elRoot);
        document.insertChild(new DocType("score-partwise",
                                         "-//Recordare//DTD MusicXML 1.1 Partwise//EN",
                                         "http://www.musicxml.org/dtds/partwise.dtd"),
                             0);

        elRoot.appendChild(new Element("part-list"));
        doFirstMeasure();
    }

    public String getMusicXMLString() {
        return document.toXML()
                       .replaceAll("<note timeStamp=\"[0-9]+\">",
                                   "<note>")
                       .replaceAll("<duration>0</duration>",
                                   "<duration>1</duration>");
    }

    private void doFirstMeasure() {
        if (elPart == null) {
            Element elScorePart = new Element("score-part");
            elScorePart.addAttribute(new Attribute("id", "V0"));
            elScorePart.appendChild(new Element("part-name"));
            elRoot.getFirstChildElement("part-list").appendChild(elScorePart);

            elPart = new Element("part");
            elPart.addAttribute(new Attribute("id", "V0"));
            elRoot.appendChild(elPart);
        }

        if (elCurMeasure == null) {
            elCurMeasure = new Element("measure");
            elCurMeasure.addAttribute(new Attribute("number", "1"));

            Element elAttributes = new Element("attributes");
            Element elDivisions = new Element("divisions");
            elDivisions.appendChild(Integer.toString(DurationHandler.DIVISIONS_PER_BEAT));
            elAttributes.appendChild(elDivisions);

            Element elKey = new Element("key");
            Element elFifths = new Element("fifths");
            elFifths.appendChild(Integer.toString(keySigHandler.fifths));
            elKey.appendChild(elFifths);
            Element elMode = new Element("mode");
            elMode.appendChild(keySigHandler.isMajor ? "major" : "minor");
            elKey.appendChild(elMode);
            elAttributes.appendChild(elKey);

            Element elTime = new Element("time");
            Element elBeats = new Element("beats");
            elBeats.appendChild(Integer.toString(durationHandler.beatsPerMeasure));
            elTime.appendChild(elBeats);
            Element elBeatType = new Element("beat-type");
            elBeatType.appendChild(Integer.toString(durationHandler.beatType));
            elTime.appendChild(elBeatType);
            elAttributes.appendChild(elTime);

            Element elStaves = new Element("staves");
            elStaves.appendChild("2");
            elAttributes.appendChild(elStaves);
            elAttributes.appendChild(clefElementFrom(1, "G", 2));
            elAttributes.appendChild(clefElementFrom(2, "F", 4));

            elCurMeasure.appendChild(elAttributes);

            Element elDirection = new Element("direction");
            elDirection.addAttribute(new Attribute("placement", "above"));
            Element elDirectionType = new Element("direction-type");
            Element elMetronome = new Element("metronome");

            Element elBeatUnit = new Element("beat-unit");
            elBeatUnit.appendChild(getBeatUnitString());
            elMetronome.appendChild(elBeatUnit);
            if (durationHandler.isTimeSignatureCompound()) {
                elMetronome.appendChild(new Element("beat-unit-dot"));
            }

            Element elPerMinute = new Element("per-minute");
            elPerMinute.appendChild(Integer.toString(durationHandler.markedTempo));
            elMetronome.appendChild(elPerMinute);

            elDirectionType.appendChild(elMetronome);
            elDirection.appendChild(elDirectionType);
            elCurMeasure.appendChild(elDirection);

            elPart.appendChild(elCurMeasure);
        }
    }

    private Element clefElementFrom(int number, String sign, int line) {
        Element elClef = new Element("clef");
        elClef.addAttribute(new Attribute("number", Integer.toString(number)));
        Element elSign = new Element("sign");
        elSign.appendChild(sign);
        elClef.appendChild(elSign);
        Element elLine = new Element("line");
        elLine.appendChild(Integer.toString(line));
        elClef.appendChild(elLine);
        return elClef;
    }

    private String getBeatUnitString() {
        return BEAT_UNIT_STRINGS.get(
                durationHandler.beatType / (durationHandler.isTimeSignatureCompound() ? 2 : 1));
    }

    @Override
    public void measureEvent() {
        int nextNumber = Integer.parseInt(allMeasures().get(allMeasures().size() - 1)
                                                       .getAttributeValue("number")) + 1;

        elCurMeasure = new Element("measure");
        elCurMeasure.addAttribute(new Attribute("number", Integer.toString(nextNumber)));
        elPart.appendChild(elCurMeasure);
    }

    @Override
    public void noteEvent(Note baseNote, List<Note> tiedNotes) {
        Element elNote = elementForNote(baseNote);
        insertNote(elNote, baseNote);

        Element measure = (Element) elNote.getParent();
        if (measure == null) {
            return;
        }

        stream(tiedNotes).forEachOrdered(
                tiedNote ->
                        streamElements(measure.getChildElements("note"))
                                .dropWhile(note -> timeStampOf(note) < tiedNote.timeStamp)
                                .findFirst()
                                .ifPresentOrElse(
                                        note -> measure.insertChild(elementForNote(tiedNote),
                                                                    measure.indexOf(note)),
                                        () -> measure.appendChild(elementForNote(tiedNote))));

        reCalculateChords(measure);
        normalizeAccidentals(measure);
    }

    private void insertNote(Element elNote, Note note) {
        Predicate<Element> matcher = note.isRest ? restMatches(note.value >= 48 ? 1 : 2, 0)
                                                 : noteMatches(note.value, 0);

        if (note.duration == 0) {
            firstNoteThatMatches(matcher)
                    .ifPresentOrElse(elOldNote -> elOldNote.getParent().removeChild(elOldNote),
                                     () -> elCurMeasure.appendChild(elNote));
        } else {
            firstNoteThatMatches(matcher)
                    .ifPresent(elOldNote -> elOldNote.getParent().replaceChild(elOldNote, elNote));
        }
    }

    private Element elementForNote(Note note) {
        Element elNote = new Element("note");
        elNote.addAttribute(new Attribute("timeStamp", Long.toString(note.timeStamp)));

        if (note.isRest) {
            elNote.appendChild(new Element("rest"));
        } else {
            Element elPitch = new Element("pitch");
            Element elStep = new Element("step");
            elStep.appendChild(keySigHandler.stepForNoteValue(note.value));
            elPitch.appendChild(elStep);

            int iAlter = keySigHandler.alterForNoteValue(note.value);
            if (iAlter != 0) {
                Element elAlter = new Element("alter");
                elAlter.appendChild(Integer.toString(iAlter));
                elPitch.appendChild(elAlter);
            }

            Element elOctave = new Element("octave");
            elOctave.appendChild(keySigHandler.octaveForNoteValue(note.value));
            elPitch.appendChild(elOctave);
            elNote.appendChild(elPitch);
        }

        Element elDuration = new Element("duration");
        elDuration.appendChild(Integer.toString(note.duration));
        elNote.appendChild(elDuration);

        boolean bTied = false;
        if (!note.isRest) {
            if (note.isEndOfTie) {
                Element elTieStop = new Element("tie");
                elTieStop.addAttribute(new Attribute("type", "stop"));
                elNote.appendChild(elTieStop);
                bTied = true;
            }
            if (note.isStartOfTie) {
                Element elTieStart = new Element("tie");
                elTieStart.addAttribute(new Attribute("type", "start"));
                elNote.appendChild(elTieStart);
                bTied = true;
            }
        }

        Element elType = new Element("type");
        elType.appendChild(note.type);
        elNote.appendChild(elType);
        if (note.isDotted) {
            elNote.appendChild(new Element("dot"));
        }

        Element elAccidental = new Element("accidental");
        elAccidental.appendChild(keySigHandler.accidentalForNoteValue(note.value));
        elNote.appendChild(elAccidental);

        Element elStaff = new Element("staff");
        elStaff.appendChild(note.value >= 48 ? "1" : "2");
        elNote.appendChild(elStaff);

        if (bTied) {
            Element elNotations = new Element("notations");

            if (note.isEndOfTie) {
                Element elTiedStop = new Element("tied");
                elTiedStop.addAttribute(new Attribute("type", "stop"));
                elNotations.appendChild(elTiedStop);
            }
            if (note.isStartOfTie) {
                Element elTiedStart = new Element("tied");
                elTiedStart.addAttribute(new Attribute("type", "start"));
                elNotations.appendChild(elTiedStart);
            }

            elNote.appendChild(elNotations);
        }

        return elNote;
    }

    private void reCalculateChords(Element measure) {
        streamElements(measure.getChildElements("backup"))
                .forEach(measure::removeChild);
        streamNotesInMeasure(measure)
                .forEach(note -> Optional.ofNullable(note.getFirstChildElement("chord"))
                                         .ifPresent(note::removeChild));

        streamNotesInMeasure(measure)
                .forEachOrdered(currentNote -> {
                    if (notMarkedAsChord(currentNote)) {
                        final int currentIndex = measure.indexOf(currentNote);

                        final List<Element> concurrentNotes = streamNotesInMeasure(measure)
                                .dropWhile(note -> note != currentNote)
                                .takeWhile(withinMarginOf(timeStampOf(currentNote)))
                                .takeWhile(MusicXmlRenderer::notMarkedAsChord)
                                .collect(toList());

                        if (concurrentNotes.size() == 1) {
                            return;
                        }

                        stream(concurrentNotes).forEach(measure::removeChild);

                        final List<Element> nonRests = stream(concurrentNotes)
                                .filter(negate(isRest))
                                .sorted(comparingInt(MusicXmlRenderer::durationOf))
                                .collect(toList());

                        for (int i = 0; i < nonRests.size(); i++) {
                            final Element note = nonRests.get(i);
                            if (i < nonRests.size() - 1) {
                                note.insertChild(new Element("chord"), 0);
                            }
                            measure.insertChild(note, currentIndex);
                        }

                        final List<Element> rests = stream(concurrentNotes)
                                .filter(isRest)
                                .sorted(comparingInt(MusicXmlRenderer::durationOf))
                                .collect(toList());

                        stream(rests).findFirst().ifPresent(rest -> {
                            if (nonRests.size() > 0) {
                                measure.insertChild(backupForDuration(durationOf(rest)),
                                                    currentIndex);
                            }
                            measure.insertChild(rest, currentIndex);
                        });
                        stream(rests).skip(1).forEachOrdered(rest -> {
                            measure.insertChild(backupForDuration(durationOf(rest)),
                                                currentIndex);
                            measure.insertChild(rest, currentIndex);
                        });
                    }
                });
    }

    private void normalizeAccidentals(Element measure) {
        //TODO
    }

    private static Stream<Element> streamNotesInMeasure(Element measure) {
        return streamElements(measure.getChildElements("note"));
    }

    private Element backupForDuration(int duration) {
        Element elBackup = new Element("backup");
        Element elDuration = new Element("duration");
        elDuration.appendChild(Integer.toString(duration));
        elBackup.appendChild(elDuration);
        return elBackup;
    }

    private static int durationOf(Element noteElement) {
        return Integer.parseInt(noteElement.getFirstChildElement("duration").getValue());
    }

    private static long timeStampOf(Element noteElement) {
        return Long.parseLong(noteElement.getAttributeValue("timeStamp"));
    }

    private Predicate<Element> withinMarginOf(long timeStamp) {
        return noteElement -> Math.abs(timeStamp - timeStampOf(noteElement)) < margin;
    }

    private static boolean notMarkedAsChord(Element noteElement) {
        return noteElement.getFirstChildElement("chord") == null
                && !isFollowedByBackup(noteElement);
    }

    private static boolean isFollowedByBackup(Element noteElement) {
        final Element parent = (Element) noteElement.getParent();
        final int indexOfNext = parent.indexOf(noteElement) + 1;
        return indexOfNext < parent.getChildCount()
                && ((Element) parent.getChild(indexOfNext)).getLocalName().equals("backup");
    }

    private static Predicate<Element> isRest =
            noteElement -> noteElement.getFirstChildElement("rest") != null;

    private static Predicate<Element> restMatches(int staff, int duration) {
        return elNote -> elNote.getFirstChildElement("rest") != null
                && elNote.getFirstChildElement("staff").getValue().equals(Integer.toString(staff))
                && elNote.getFirstChildElement("duration")
                         .getValue().equals(Integer.toString(duration));
    }

    private Predicate<Element> noteMatches(int value, int duration) {
        return elNote -> elNote.getFirstChildElement("rest") == null
                && pitchMatches(elNote.getFirstChildElement("pitch"), value)
                && elNote.getFirstChildElement("duration")
                         .getValue().equals(Integer.toString(duration));
    }

    private boolean pitchMatches(Element elPitch, int value) {
        return elPitch.getFirstChildElement("step").getValue()
                      .equals(keySigHandler.stepForNoteValue(value))
                && elPitch.getFirstChildElement("octave").getValue()
                          .equals(keySigHandler.octaveForNoteValue(value))
                && alterMatches(elPitch.getFirstChildElement("alter"), value);
    }

    private boolean alterMatches(Element elAlter, int value) {
        int alter = keySigHandler.alterForNoteValue(value);
        return alter == 0 ? elAlter == null
                          : elAlter != null && elAlter.getValue().equals(Integer.toString(alter));
    }

    private Optional<Element> firstNoteThatMatches(Predicate<Element> predicate) {
        return streamElements(allMeasures())
                .flatMap(measure -> streamElements(measure.getChildElements("note")))
                .filter(predicate).findFirst();
    }

    private Elements allMeasures() {
        return elPart.getChildElements("measure");
    }

    private static Stream<Element> streamElements(Elements elements) {
        return stream(new ElementsSpliterator(elements), false);
    }

    private static class ElementsSpliterator extends AbstractSpliterator<Element> {

        private Elements elements;
        private int current;

        private ElementsSpliterator(Elements elements) {
            super(elements.size(), Spliterator.ORDERED | Spliterator.SIZED | Spliterator.IMMUTABLE);
            this.elements = elements;
            current = 0;
        }

        @Override
        public boolean tryAdvance(Consumer<? super Element> action) {
            if (current < elements.size()) {
                action.accept(elements.get(current));
                current++;
                return true;
            } else {
                return false;
            }
        }
    }

}
