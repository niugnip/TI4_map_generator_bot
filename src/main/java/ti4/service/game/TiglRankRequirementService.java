package ti4.service.game;

import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.experimental.UtilityClass;
import net.dv8tion.jda.api.entities.User;
import org.apache.commons.lang3.StringUtils;
import ti4.helpers.TIGLHelper;
import ti4.helpers.TIGLHelper.TIGLRank;

/**
 * A poster's minimum TIGL rank for a game recruitment post. The requirement lives in the bot's launch
 * message next to the fun game name, so it survives every re-render of the player list and needs no
 * extra storage. It is enforced when players are added through the launch post buttons, not at launch.
 */
@UtilityClass
public class TiglRankRequirementService {

    private static final String LINE_PREFIX = "-# Minimum TIGL rank: **";
    private static final String LINE_SUFFIX = "** or higher - players below this rank can't join.";

    private static final String RANK_NAMES =
            "minister|agent|commander|hero|thrall|acolyte|legionnaire|starlancer|gene-?sorcerer|ixth-?lord|archon";

    // Deliberately conservative: a rank name on its own ("Hero rank-up game") is not a requirement.
    private static final List<Pattern> TITLE_PATTERNS = List.of(
            // "Agent+", "Agent +"
            Pattern.compile("\\b(" + RANK_NAMES + ")\\s*\\+", Pattern.CASE_INSENSITIVE),
            // "Agent or higher", "Agent and above", "Agent or better"
            Pattern.compile(
                    "\\b(" + RANK_NAMES + ")\\s+(?:or|and)\\s+(?:higher|above|up|better)\\b", Pattern.CASE_INSENSITIVE),
            // "min rank Agent", "minimum: Agent", "min. Agent"
            Pattern.compile(
                    "\\bmin(?:imum)?\\.?\\s*(?:rank)?\\s*:?\\s*(" + RANK_NAMES + ")\\b", Pattern.CASE_INSENSITIVE),
            // "Agent minimum", "Agent min"
            Pattern.compile("\\b(" + RANK_NAMES + ")\\s+min(?:imum)?\\b", Pattern.CASE_INSENSITIVE));

    /**
     * Reads a minimum rank out of a post title such as "6p 10VP Agent+" or "Commander or higher".
     *
     * @return the detected rank, or null when the title states no requirement
     */
    public static TIGLRank detectFromPostTitle(String title) {
        if (StringUtils.isBlank(title)) return null;
        for (Pattern pattern : TITLE_PATTERNS) {
            Matcher matcher = pattern.matcher(title);
            if (matcher.find()) {
                TIGLRank rank = TIGLRank.fromString(matcher.group(1));
                if (rank != null && rank.getIndex() > 0) return rank;
            }
        }
        return null;
    }

    public static String renderLaunchMessageLine(TIGLRank minimumRank) {
        return LINE_PREFIX + minimumRank.getShortName() + LINE_SUFFIX;
    }

    /**
     * @return the minimum rank recorded in a launch post message, or null when none is set
     */
    public static TIGLRank parseFromLaunchMessage(String launchMessage) {
        if (launchMessage == null) return null;
        return TIGLRank.fromString(StringUtils.substringBetween(launchMessage, LINE_PREFIX, "**"));
    }

    /** Ranks a poster can require: every real ladder rank above Unranked, standard ladder first. */
    public static List<TIGLRank> selectableMinimumRanks() {
        return Arrays.stream(TIGLRank.values())
                .filter(rank -> rank.getIndex() > 0 && rank != TIGLRank.EMPEROR)
                .sorted(Comparator.comparing(TIGLRank::isFracturedLadder).thenComparing(TIGLRank::getIndex))
                .toList();
    }

    public static String describe(TIGLRank rank) {
        return rank.isFracturedLadder() ? rank.getShortName() + " (Fractured)" : rank.getShortName();
    }

    /**
     * The requirement implied by the ranks a matchmaking search is queued for: players qualify at the
     * lowest listed rank, and a list that includes Unranked requires nothing.
     */
    public static TIGLRank lowestOf(List<String> rankNames) {
        TIGLRank lowest = rankNames.stream()
                .map(TIGLRank::fromString)
                .filter(Objects::nonNull)
                .min(Comparator.comparing(TIGLRank::getIndex))
                .orElse(null);
        return lowest == null || lowest.getIndex() <= 0 ? null : lowest;
    }

    /**
     * @return why the user may not join a post with this minimum rank, phrased to follow "can't join because",
     *         or empty when there is no requirement or the user meets it
     */
    public static Optional<String> findJoinBlocker(TIGLRank minimumRank, User user) {
        if (minimumRank == null || user == null) return Optional.empty();
        TIGLRank current = TIGLHelper.getUsersHighestTIGLRank(user, minimumRank.isFracturedLadder());
        if (current.getIndex() >= minimumRank.getIndex()) return Optional.empty();
        return Optional.of("this post requires TIGL rank **" + describe(minimumRank) + "** or higher (current rank: **"
                + current.getShortName() + "**).");
    }
}
