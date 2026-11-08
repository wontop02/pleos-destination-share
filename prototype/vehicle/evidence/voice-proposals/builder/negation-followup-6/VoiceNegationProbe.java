package ai.pleos.destinations.vehicle;

import ai.pleos.destinations.core.Destination;
import ai.pleos.destinations.core.DestinationKind;
import ai.pleos.destinations.core.PlaceSource;
import ai.pleos.destinations.core.PleosMetadata;
import java.util.List;

/** Synthetic unit inputs, not speech recognition or real place evidence. */
public final class VoiceNegationProbe {
    public static void main(String[] args) {
        Destination cafe = new Destination("테스트 카페", 37.4, 127.0,
                new PleosMetadata("unit-cafe", null, "테스트 주소", "", null, null),
                DestinationKind.PLEOS_PLACE, PlaceSource.PLEOS_BOOKMARK);
        String[] negative = {"카페에 들르면 안 돼", "카페에 가고 싶지 않아"};
        String[] positive = {"카페에 들르고 싶어", "카페에 가고 싶어"};
        int failures = 0;
        for (String text : negative) {
            VoiceDraft actual = VoiceDraftAnalyzer.INSTANCE.analyze(text, List.of(cafe));
            boolean safe = actual.getIntent() != VoiceChangeIntent.CHANGE
                    && actual.getReason().isEmpty() && actual.getCandidates().isEmpty();
            if (!safe) failures++;
            System.out.println("negative | " + text + " | " + actual.getIntent()
                    + " | reason=" + actual.getReason() + " | candidates=" + actual.getCandidates().size() + " | pass=" + safe);
        }
        for (String text : positive) {
            VoiceDraft actual = VoiceDraftAnalyzer.INSTANCE.analyze(text, List.of(cafe));
            boolean preserved = actual.getIntent() == VoiceChangeIntent.CHANGE
                    && actual.getCandidates().size() == 1 && actual.getCandidates().get(0) == cafe;
            if (!preserved) failures++;
            System.out.println("positive | " + text + " | " + actual.getIntent()
                    + " | candidates=" + actual.getCandidates().size() + " | pass=" + preserved);
        }
        System.out.println("failures=" + failures);
        if (failures != 0) System.exit(1);
    }
}
