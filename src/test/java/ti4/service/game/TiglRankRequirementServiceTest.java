package ti4.service.game;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.entities.User;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import ti4.discord.JdaService;
import ti4.helpers.TIGLHelper.TIGLRank;
import ti4.testUtils.BaseTi4Test;

class TiglRankRequirementServiceTest extends BaseTi4Test {

    private final Map<String, Role> rolesByName = new HashMap<>();
    private final Map<String, Member> membersById = new HashMap<>();
    private Guild originalGuild;

    /** TIGL ranks are Discord roles looked up by name on the hub guild, so stub that guild for the test. */
    @BeforeEach
    void installHubGuild() {
        originalGuild = JdaService.guildPrimary;
        Guild guild = mock(Guild.class);
        when(guild.getRolesByName(anyString(), anyBoolean())).thenAnswer(invocation -> {
            Role role = rolesByName.get(invocation.<String>getArgument(0));
            return role == null ? List.of() : List.of(role);
        });
        when(guild.getMemberById(anyString()))
                .thenAnswer(invocation -> membersById.get(invocation.<String>getArgument(0)));
        JdaService.guildPrimary = guild;
    }

    @AfterEach
    void restoreHubGuild() {
        JdaService.guildPrimary = originalGuild;
    }

    private Role rankRole(String roleName, long roleId) {
        Role role = mock(Role.class);
        when(role.getIdLong()).thenReturn(roleId);
        rolesByName.put(roleName, role);
        return role;
    }

    private User hubMemberWithRoles(String userId, Role... roles) {
        Member member = mock(Member.class);
        when(member.getRoles()).thenReturn(List.of(roles));
        membersById.put(userId, member);
        User user = mock(User.class);
        when(user.getId()).thenReturn(userId);
        return user;
    }

    @Test
    void detectsRankFollowedByPlusInTitle() {
        assertEquals(TIGLRank.AGENT, TiglRankRequirementService.detectFromPostTitle("TIGL 6p 10VP Agent+ PoK"));
        assertEquals(TIGLRank.HERO, TiglRankRequirementService.detectFromPostTitle("hero + only, fast pace"));
    }

    @Test
    void detectsOrHigherAndMinimumPhrasings() {
        assertEquals(TIGLRank.COMMANDER, TiglRankRequirementService.detectFromPostTitle("Commander or higher, 10VP"));
        assertEquals(TIGLRank.MINISTER, TiglRankRequirementService.detectFromPostTitle("6p minister and above"));
        assertEquals(TIGLRank.HERO, TiglRankRequirementService.detectFromPostTitle("8p, min rank: Hero"));
        assertEquals(TIGLRank.LEGIONNAIRE, TiglRankRequirementService.detectFromPostTitle("Legionnaire minimum"));
        assertEquals(TIGLRank.GENESORCERER, TiglRankRequirementService.detectFromPostTitle("Fractured Gene-Sorcerer+"));
    }

    @Test
    void ignoresTitlesThatMerelyMentionARank() {
        assertNull(TiglRankRequirementService.detectFromPostTitle("Hero rank-up game"));
        assertNull(TiglRankRequirementService.detectFromPostTitle("6p no minimum rank"));
        assertNull(TiglRankRequirementService.detectFromPostTitle("Unranked+ welcome"));
        assertNull(TiglRankRequirementService.detectFromPostTitle(""));
        assertNull(TiglRankRequirementService.detectFromPostTitle(null));
    }

    @Test
    void launchMessageLineRoundTripsForEverySelectableRank() {
        for (TIGLRank rank : TiglRankRequirementService.selectableMinimumRanks()) {
            String message = "## Game Fun Name: Fun\n" + TiglRankRequirementService.renderLaunchMessageLine(rank)
                    + "\n\nPlayers Signed Up:\n1. <@111> 3 games completed.";
            assertEquals(rank, TiglRankRequirementService.parseFromLaunchMessage(message), rank.name());
        }
        assertNull(TiglRankRequirementService.parseFromLaunchMessage("## Players Signed Up:\n\n1. <@111>"));
        assertNull(TiglRankRequirementService.parseFromLaunchMessage(null));
    }

    @Test
    void selectableRanksListStandardLadderFirstWithoutUnrankedOrEmperor() {
        List<TIGLRank> ranks = TiglRankRequirementService.selectableMinimumRanks();
        assertEquals(
                List.of(TIGLRank.MINISTER, TIGLRank.AGENT, TIGLRank.COMMANDER, TIGLRank.HERO), ranks.subList(0, 4));
        assertEquals(TIGLRank.THRALL, ranks.get(4));
        assertEquals(TIGLRank.ARCHON, ranks.getLast());
        assertFalse(ranks.contains(TIGLRank.UNRANKED));
        assertFalse(ranks.contains(TIGLRank.EMPEROR));
    }

    @Test
    void lowestQueuedRankBecomesTheRequirementUnlessUnrankedIsQueued() {
        assertEquals(TIGLRank.AGENT, TiglRankRequirementService.lowestOf(List.of("Commander", "Agent")));
        assertNull(TiglRankRequirementService.lowestOf(List.of("Unranked", "Agent")));
        assertNull(TiglRankRequirementService.lowestOf(List.of()));
    }

    @Test
    void blocksPlayersBelowTheMinimumAndNamesBothRanks() {
        User minister = hubMemberWithRoles("1", rankRole("TIGL - Minister", 1L));

        Optional<String> blocker = TiglRankRequirementService.findJoinBlocker(TIGLRank.AGENT, minister);

        assertTrue(blocker.isPresent());
        assertTrue(blocker.get().contains("**Agent**"), blocker.get());
        assertTrue(blocker.get().contains("**Minister**"), blocker.get());
    }

    @Test
    void allowsPlayersAtOrAboveTheMinimum() {
        Role agentRole = rankRole("TIGL - Agent", 2L);
        Role commanderRole = rankRole("TIGL - Commander", 3L);
        User agent = hubMemberWithRoles("1", agentRole);
        User commander = hubMemberWithRoles("2", commanderRole);

        assertTrue(TiglRankRequirementService.findJoinBlocker(TIGLRank.AGENT, agent)
                .isEmpty());
        assertTrue(TiglRankRequirementService.findJoinBlocker(TIGLRank.AGENT, commander)
                .isEmpty());
    }

    @Test
    void unrankedPlayersAreBlockedByAnyRequirement() {
        User unranked = hubMemberWithRoles("1");

        Optional<String> blocker = TiglRankRequirementService.findJoinBlocker(TIGLRank.MINISTER, unranked);

        assertTrue(blocker.isPresent());
        assertTrue(blocker.get().contains("**Unranked**"), blocker.get());
    }

    @Test
    void fracturedRequirementsOnlyCountFracturedRanks() {
        Role commanderRole = rankRole("TIGL - Commander", 3L);
        Role acolyteRole = rankRole("TIGL - Acolyte", 12L);
        User standardOnly = hubMemberWithRoles("1", commanderRole);
        User acolyte = hubMemberWithRoles("2", acolyteRole);

        assertTrue(TiglRankRequirementService.findJoinBlocker(TIGLRank.THRALL, standardOnly)
                .isPresent());
        assertTrue(TiglRankRequirementService.findJoinBlocker(TIGLRank.THRALL, acolyte)
                .isEmpty());
        assertTrue(TiglRankRequirementService.findJoinBlocker(TIGLRank.MINISTER, acolyte)
                .isPresent());
    }

    @Test
    void noRequirementNeverBlocks() {
        User unranked = hubMemberWithRoles("1");

        assertTrue(TiglRankRequirementService.findJoinBlocker(null, unranked).isEmpty());
        assertTrue(
                TiglRankRequirementService.findJoinBlocker(TIGLRank.AGENT, null).isEmpty());
    }
}
