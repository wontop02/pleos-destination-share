import java.lang.reflect.*;
import java.util.*;
public class NegativeDraftProbe {
  public static void main(String[] args) throws Exception {
    Class<?> analyzer = Class.forName("ai.pleos.destinations.vehicle.VoiceDraftAnalyzer");
    Object instance = analyzer.getField("INSTANCE").get(null);
    Method analyze = analyzer.getMethod("analyze", String.class, List.class);
    for (String text : List.of("카페에 들르면 안 돼", "카페에 가고 싶지 않아", "카페 가지 말자", "카페 들르는 거 어때?")) {
      Object result = analyze.invoke(instance, text, Collections.emptyList());
      Class<?> draft = result.getClass();
      System.out.println("input=" + text);
      System.out.println("intent=" + draft.getMethod("getIntent").invoke(result));
      System.out.println("reason=" + draft.getMethod("getReason").invoke(result));
      System.out.println("candidateCount=" + ((List<?>)draft.getMethod("getCandidates").invoke(result)).size());
    }
  }
}
