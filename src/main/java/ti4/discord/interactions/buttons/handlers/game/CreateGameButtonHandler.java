package ti4.discord.interactions.buttons.handlers.game;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import lombok.experimental.UtilityClass;
import net.dv8tion.jda.api.components.label.Label;
import net.dv8tion.jda.api.components.selections.EntitySelectMenu;
import net.dv8tion.jda.api.components.selections.EntitySelectMenu.SelectTarget;
import net.dv8tion.jda.api.components.selections.SelectOption;
import net.dv8tion.jda.api.components.selections.StringSelectMenu;
import net.dv8tion.jda.api.components.textinput.TextInput;
import net.dv8tion.jda.api.components.textinput.TextInputStyle;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.channel.concrete.Category;
import net.dv8tion.jda.api.entities.channel.concrete.ThreadChannel;
import net.dv8tion.jda.api.events.interaction.ModalInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.interactions.Interaction;
import net.dv8tion.jda.api.interactions.modals.ModalMapping;
import net.dv8tion.jda.api.modals.Modal;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.function.Consumers;
import ti4.discord.JdaService;
import ti4.discord.interactions.commands.CommandHelper;
import ti4.discord.interactions.routing.ButtonHandler;
import ti4.discord.interactions.routing.ModalHandler;
import ti4.game.Game;
import ti4.game.persistence.GameManager;
import ti4.game.persistence.ManagedPlayer;
import ti4.helpers.SearchGameHelper;
import ti4.helpers.TIGLHelper.TIGLRank;
import ti4.logging.BotLogger;
import ti4.message.MessageHelper;
import ti4.service.game.CreateGameService;
import ti4.service.game.TiglRankRequirementService;
import ti4.settings.users.UserSettingsManager;
import ti4.spring.service.statistics.AverageTurnTimeService;
import ti4.spring.service.statistics.UserGameInfoService;
import ti4.spring.service.statistics.matchmaking.queue.MatchmakerService;
import ti4.spring.service.statistics.matchmaking.queue.MatchmakingQueueSearchService;
import ti4.spring.service.statistics.matchmaking.queue.PlayerSearchCriteria;
import ti4.spring.service.statistics.matchmaking.queue.PlayerSearchService;

@UtilityClass
public class CreateGameButtonHandler {

    @ButtonHandler("launchGame")
    public static void createGameChannelsButton(ButtonInteractionEvent event) {
        List<Member> members = new ArrayList<>();
        members.add(event.getMember());
        Member member = event.getMember();

        boolean owner = false;
        if (event.getChannel() instanceof ThreadChannel threadChannel) {
            if (threadChannel.getOwnerId().equals(member.getId())) {
                owner = true;
            }
        }

        int completedAndOngoingAmount =
                SearchGameHelper.searchGames(member.getUser(), null, false, true, false, true, false, true, true, true);
        if (completedAndOngoingAmount < 1 && !owner) {
            MessageHelper.sendMessageToChannel(
                    event.getMessageChannel(), member.getUser().getAsMention() + """
                     You need to have completed at least one game (or be currently in a game) to create new games via this button. \
                    This is to prevent mistakes by people who don't know what they're doing. There are a few ways to get around this:
                    1) Have someone else who has completed a game press the button for you; or
                    2) Ping a bothelper for help; or
                    3) Have the player who made the post press the button.""");
            return;
        }

        createGameAndChannels(event);
    }

    @ModalHandler("signupModal")
    public static void finishSignup(ModalInteractionEvent event) {
        List<Member> members = event.getValue("players").getAsMentions().getMembers();
        List<Member> membersOG = fetchMembersFromMessage(event);
        List<String> blocked = new ArrayList<>();
        for (Member member : members) {
            if (membersOG.contains(member)) continue;
            Optional<String> blocker =
                    findJoinBlocker(event.getMessage().getContentRaw(), event.getChannelId(), member, membersOG);
            if (blocker.isPresent()) {
                blocked.add(member.getAsMention() + " can't join because " + blocker.get());
                continue;
            }
            membersOG.add(member);
            MatchmakerService.get().leaveQueue(member.getId());
            MessageHelper.sendMessageToEventChannel(event, member.getAsMention() + " joined the game.");
        }
        event.getMessage()
                .editMessage(regenerateLaunchMessage(event.getMessage().getContentRaw(), membersOG))
                .queue();
        MatchmakingQueueSearchService.get().updateForRoster(event.getChannelId(), memberIds(membersOG));
        if (!blocked.isEmpty()) {
            event.getHook()
                    .setEphemeral(true)
                    .sendMessage(String.join("\n", blocked))
                    .queue(Consumers.nop(), BotLogger::catchRestError);
        }
    }

    @ButtonHandler(value = "editPlayers~MDL", save = false)
    public static void editPlayers(ButtonInteractionEvent event) {
        String modalID = "signupModal";
        String fieldID = "players";
        EntitySelectMenu menu = EntitySelectMenu.create(fieldID, SelectTarget.USER)
                .setPlaceholder("Choose your players") // shows the placeholder indicating what this menu is for
                .setRequiredRange(1, 8)
                .build();

        Modal modal = Modal.create(modalID, "Players For The Game")
                .addComponents(Label.of("Select Players", menu))
                .build();
        event.replyModal(modal).queue(Consumers.nop(), BotLogger::catchRestError);
    }

    @ModalHandler("removeSignupModal")
    public static void removeSignup(ModalInteractionEvent event) {
        List<Member> members = event.getValue("players").getAsMentions().getMembers();
        List<Member> membersOG = fetchMembersFromMessage(event);
        for (Member member : members) {
            if (!membersOG.contains(member)) continue;
            membersOG.remove(member);
            MessageHelper.sendMessageToEventChannel(event, member.getAsMention() + " was removed from the game.");
        }
        event.getMessage()
                .editMessage(regenerateLaunchMessage(event.getMessage().getContentRaw(), membersOG))
                .queue();
        MatchmakingQueueSearchService.get().updateForRoster(event.getChannelId(), memberIds(membersOG));
    }

    @ModalHandler("addSillyNameModal")
    public static void addSillyNameModal(ModalInteractionEvent event) {
        String sillyName = event.getValue("sillyName").getAsString();
        List<Member> membersOG = fetchMembersFromMessage(event);
        event.getMessage()
                .editMessage(generateMemberListMessage(membersOG, sillyName, fetchMinimumTiglRankFromMessage(event)))
                .queue();
    }

    @ButtonHandler(value = "addSillyName~MDL", save = false)
    public static void addSillyName(ButtonInteractionEvent event) {
        String modalID = "addSillyNameModal";
        String fieldID = "sillyName";
        TextInput summary = TextInput.create(fieldID, TextInputStyle.PARAGRAPH)
                .setPlaceholder("Specify a fun game name here")
                .setValue(CreateGameService.autoGenerateGameName())
                .build();
        Modal modal = Modal.create(modalID, "Fun Game Name")
                .addComponents(Label.of("Edit game name", summary))
                .build();
        event.replyModal(modal).queue(Consumers.nop(), BotLogger::catchRestError);
    }

    private static final String MIN_TIGL_RANK_MODAL_ID = "setMinTiglRankModal";
    private static final String MIN_TIGL_RANK_FIELD_ID = "minTiglRank";
    private static final String NO_MIN_TIGL_RANK = "none";
    private static final String MIN_TIGL_RANK_NOT_ALLOWED =
            "Only the player who created this post, the first signed-up player, or staff can change the minimum TIGL rank.";

    @ButtonHandler(value = "setMinTiglRank~MDL", save = false)
    public static void setMinimumTiglRank(ButtonInteractionEvent event) {
        if (!canSetMinimumTiglRank(event, event.getMessage())) {
            event.reply(MIN_TIGL_RANK_NOT_ALLOWED).setEphemeral(true).queue(Consumers.nop(), BotLogger::catchRestError);
            return;
        }
        TIGLRank current = fetchMinimumTiglRankFromMessage(event);
        StringSelectMenu.Builder menu = StringSelectMenu.create(MIN_TIGL_RANK_FIELD_ID)
                .setRequiredRange(1, 1)
                .addOptions(SelectOption.of("No requirement", NO_MIN_TIGL_RANK));
        for (TIGLRank rank : TiglRankRequirementService.selectableMinimumRanks()) {
            menu.addOptions(SelectOption.of(TiglRankRequirementService.describe(rank), rank.toString()));
        }
        menu.setDefaultValues(List.of(current == null ? NO_MIN_TIGL_RANK : current.toString()));
        Modal modal = Modal.create(MIN_TIGL_RANK_MODAL_ID, "Minimum TIGL Rank")
                .addComponents(Label.of("Players below this rank can't join", menu.build()))
                .build();
        event.replyModal(modal).queue(Consumers.nop(), BotLogger::catchRestError);
    }

    @ModalHandler(MIN_TIGL_RANK_MODAL_ID)
    public static void setMinimumTiglRankModal(ModalInteractionEvent event) {
        if (!canSetMinimumTiglRank(event, event.getMessage())) {
            event.getHook()
                    .setEphemeral(true)
                    .sendMessage(MIN_TIGL_RANK_NOT_ALLOWED)
                    .queue(Consumers.nop(), BotLogger::catchRestError);
            return;
        }
        ModalMapping mapping = event.getValue(MIN_TIGL_RANK_FIELD_ID);
        String selected = mapping == null || mapping.getAsStringList().isEmpty()
                ? NO_MIN_TIGL_RANK
                : mapping.getAsStringList().getFirst();
        TIGLRank minimumRank = NO_MIN_TIGL_RANK.equals(selected) ? null : TIGLRank.fromString(selected);

        String content = event.getMessage().getContentRaw();
        List<Member> members = fetchMembersFromMessage(event);
        event.getMessage()
                .editMessage(generateMemberListMessage(members, fetchSillyNameFromMessage(content), minimumRank))
                .queue(Consumers.nop(), BotLogger::catchRestError);

        String who = event.getUser().getEffectiveName();
        if (minimumRank == null) {
            MessageHelper.sendMessageToEventChannel(event, who + " removed the minimum TIGL rank requirement.");
            return;
        }
        StringBuilder announcement = new StringBuilder(
                who + " set the minimum TIGL rank to **" + TiglRankRequirementService.describe(minimumRank) + "**.");
        List<String> below = members.stream()
                .filter(member -> TiglRankRequirementService.findJoinBlocker(minimumRank, member.getUser())
                        .isPresent())
                .map(Member::getAsMention)
                .toList();
        if (!below.isEmpty()) {
            announcement
                    .append("\nAlready signed up but below this rank: ")
                    .append(String.join(", ", below))
                    .append(". Use **Remove Players** if they shouldn't stay.");
        }
        MessageHelper.sendMessageToEventChannel(event, announcement.toString());
    }

    /**
     * The poster owns the requirement. Matchmade posts are owned by the bot, so the first signed-up player
     * (who becomes the game owner at launch) may set it too, as may staff.
     */
    private static boolean canSetMinimumTiglRank(Interaction event, Message launchMessage) {
        if (isStaff(event)) return true;
        String userId = event.getUser().getId();
        if (event.getChannel() instanceof ThreadChannel thread && userId.equals(thread.getOwnerId())) {
            return true;
        }
        List<Member> members = fetchMembersFromMessage(launchMessage, event.getGuild());
        return !members.isEmpty() && members.getFirst().getId().equals(userId);
    }

    @ButtonHandler(value = "removePlayers~MDL", save = false)
    public static void removePlayers(ButtonInteractionEvent event) {
        String modalID = "removeSignupModal";
        String fieldID = "players";
        EntitySelectMenu menu = EntitySelectMenu.create(fieldID, SelectTarget.USER)
                .setPlaceholder(
                        "Choose your players to remove") // shows the placeholder indicating what this menu is for
                .setRequiredRange(1, 8)
                .build();

        Modal modal = Modal.create(modalID, "Removing Players In The Game")
                .addComponents(Label.of("Select Players", menu))
                .build();
        event.replyModal(modal).queue(Consumers.nop(), BotLogger::catchRestError);
    }

    private static List<Member> fetchMembersFromMessage(String buttonMsg, Guild guild) {
        List<Member> members = new ArrayList<>();
        for (int i = 0; i < StringUtils.countMatches(buttonMsg, "<@"); i++) {
            String user = buttonMsg.split("@")[i + 1];
            user = StringUtils.substringBefore(user, ">");
            Member member = guild.getMemberById(user);
            if (member != null) {
                members.add(member);
            }
        }
        return members;
    }

    public static List<Member> fetchMembersFromMessage(Message message, Guild guild) {
        return fetchMembersFromMessage(message.getContentRaw(), guild);
    }

    private static Optional<String> findQueueJoinBlocker(
            String threadId, String joiningUserId, List<Member> existingMembers) {
        return MatchmakingQueueSearchService.get().findJoinBlocker(threadId, joiningUserId, memberIds(existingMembers));
    }

    private static List<String> memberIds(List<Member> members) {
        return members.stream().map(Member::getId).toList();
    }

    private static List<Member> fetchMembersFromMessage(ButtonInteractionEvent event) {
        return fetchMembersFromMessage(event.getMessage().getContentRaw(), event.getGuild());
    }

    private static List<Member> fetchMembersFromMessage(ModalInteractionEvent event) {
        return fetchMembersFromMessage(event.getMessage().getContentRaw(), event.getGuild());
    }

    private static String fetchSillyNameFromMessage(String buttonMsg) {
        return StringUtils.substringBetween(buttonMsg, "Game Fun Name: ", "\n");
    }

    private static String fetchSillyNameFromMessage(ModalInteractionEvent event) {
        return fetchSillyNameFromMessage(event.getMessage().getContentRaw());
    }

    private static String fetchSillyNameFromMessage(ButtonInteractionEvent event) {
        return fetchSillyNameFromMessage(event.getMessage().getContentRaw());
    }

    private static TIGLRank fetchMinimumTiglRankFromMessage(String buttonMsg) {
        return TiglRankRequirementService.parseFromLaunchMessage(buttonMsg);
    }

    private static TIGLRank fetchMinimumTiglRankFromMessage(ModalInteractionEvent event) {
        return fetchMinimumTiglRankFromMessage(event.getMessage().getContentRaw());
    }

    private static TIGLRank fetchMinimumTiglRankFromMessage(ButtonInteractionEvent event) {
        return fetchMinimumTiglRankFromMessage(event.getMessage().getContentRaw());
    }

    /** Re-renders the launch post for a changed roster, keeping the fun name and rank requirement it carries. */
    private static String regenerateLaunchMessage(String buttonMsg, List<Member> members) {
        return generateMemberListMessage(
                members, fetchSillyNameFromMessage(buttonMsg), fetchMinimumTiglRankFromMessage(buttonMsg));
    }

    /** The post's own rank requirement is checked before any matchmaking queue criteria. */
    private static Optional<String> findJoinBlocker(
            String buttonMsg, String threadId, Member joining, List<Member> existingMembers) {
        Optional<String> rankBlocker = TiglRankRequirementService.findJoinBlocker(
                fetchMinimumTiglRankFromMessage(buttonMsg), joining.getUser());
        if (rankBlocker.isPresent()) return rankBlocker;
        return findQueueJoinBlocker(threadId, joining.getId(), existingMembers);
    }

    public static String generateMemberListMessage(List<Member> members, String gameFunName) {
        return generateMemberListMessage(members, gameFunName, null, true);
    }

    public static String generateMemberListMessage(List<Member> members, String gameFunName, TIGLRank minimumTiglRank) {
        return generateMemberListMessage(members, gameFunName, minimumTiglRank, true);
    }

    public static String generateMemberListMessage(List<Member> members, String gameFunName, boolean ping) {
        return generateMemberListMessage(members, gameFunName, null, ping);
    }

    public static String generateMemberListMessage(
            List<Member> members, String gameFunName, TIGLRank minimumTiglRank, boolean ping) {
        StringBuilder memberList = new StringBuilder();

        // The rank line sits directly under the heading so the parsers for the fun name (up to the
        // first line break) and the players (user mentions) are unaffected by it.
        String rankLine =
                minimumTiglRank == null ? "" : TiglRankRequirementService.renderLaunchMessageLine(minimumTiglRank);
        if (gameFunName == null || gameFunName.isEmpty()) {
            if (ping) {
                memberList.append("## Players Signed Up:\n");
            } else {
                memberList.append("## Players:\n");
            }
            memberList.append(rankLine);
        } else {
            if (ping) {
                memberList.append("## Game Fun Name: ").append(gameFunName.replace(":", ""));
            } else {
                memberList.append(gameFunName.replace(":", ""));
            }
            if (!rankLine.isEmpty()) {
                memberList.append('\n').append(rankLine);
            }
            memberList.append(ping ? "\n\nPlayers Signed Up:" : "\n\nPlayers:");
        }

        StringBuilder activityList = new StringBuilder();

        var userIds = members.stream().map(Member::getId).toList();
        Map<String, Long> userIdsToAverageTurnTimes =
                AverageTurnTimeService.getBean().getUserIdsToAverageTurnTimes(userIds);
        int playerNumber = 1;
        for (Member member : members) {
            String mention = ping ? member.getUser().getAsMention() : member.getEffectiveName();
            memberList.append('\n').append(playerNumber).append(". ").append(mention);

            ManagedPlayer managedPlayer = GameManager.getManagedPlayer(member.getId());
            int ongoingAmount = UserGameInfoService.countOngoingGamesThatAffectJoinLimit(managedPlayer);
            int completedGames = UserGameInfoService.countCompletedGamesThatAffectJoinLimit(managedPlayer);
            var userSettings = UserSettingsManager.get(member.getId());
            if (managedPlayer != null) {
                String trackRecord = userSettings.getTrackRecord();
                int droppedGames = 0;
                droppedGames -= StringUtils.countMatches(trackRecord, "replaced");
                droppedGames -= StringUtils.countMatches(trackRecord, "Dropped");
                completedGames += droppedGames;
            }
            if (UserGameInfoService.isOverStandardGameLimit(managedPlayer)) {
                memberList
                        .append("⚠️ (Above or equal game limit: ")
                        .append(ongoingAmount)
                        .append(" ongoing, ")
                        .append(completedGames + 3)
                        .append("-game limit) ");
            } else {
                memberList.append(' ').append(completedGames).append(" games completed. ");
            }
            List<Integer> threeFastestDays = UserGameInfoService.get()
                    .getUsersThreeFastestDaysToComplete6PlayerGames(
                            member.getUser().getId());
            if (!threeFastestDays.isEmpty()) {
                memberList.append(" (");
                for (int i = 0; i < threeFastestDays.size() && i < 3; i++) {
                    memberList.append("`").append(threeFastestDays.get(i)).append("`");
                    if (i != threeFastestDays.size() - 1) memberList.append(", ");
                }
                memberList.append(" fastest 6 player game length(s) in days) ");
            }
            String activeHoursSummary = userSettings.summarizeActiveHoursEmoji(userSettings.getActiveHours());
            if (activeHoursSummary != null) {
                if (activityList.isEmpty()) {
                    activityList
                            .append("\n### Players Active Hours (starting from ")
                            .append("<t:" + 1767225600L)
                            .append(":t>")
                            .append("):\n");
                }

                activityList.append('\n').append(playerNumber).append(". ").append(activeHoursSummary);
            }
            playerNumber++;
        }
        return memberList.toString() + activityList;
    }

    @ButtonHandler(value = "joinGameList", save = false)
    public static void joinGameList(ButtonInteractionEvent event) {
        List<Member> members = fetchMembersFromMessage(event);
        if (!members.contains(event.getMember())) {
            Optional<String> blocker = findJoinBlocker(
                    event.getMessage().getContentRaw(), event.getChannelId(), event.getMember(), members);
            if (blocker.isPresent()) {
                event.getHook()
                        .setEphemeral(true)
                        .sendMessage("You can't join this game because " + blocker.get())
                        .queue(Consumers.nop(), BotLogger::catchRestError);
                return;
            }
            members.add(event.getMember());
        }
        event.getMessage()
                .editMessage(regenerateLaunchMessage(event.getMessage().getContentRaw(), members))
                .queue(Consumers.nop(), BotLogger::catchRestError);
        MessageHelper.sendMessageToEventChannel(event, event.getUser().getEffectiveName() + " joined the game.");
        MatchmakingQueueSearchService.get().updateForRoster(event.getChannelId(), memberIds(members));
        if (MatchmakerService.get().leaveQueue(event.getUser().getId())) {
            event.getHook()
                    .setEphemeral(true)
                    .sendMessage("Because you joined a game, you are no longer queued to find a game.")
                    .queue(Consumers.nop(), BotLogger::catchRestError);
        }
    }

    public static int addPlayersFromQueueSearch(ModalInteractionEvent event, PlayerSearchCriteria criteria) {
        return addPlayersFromQueueSearch(event.getGuild(), event.getMessage(), criteria);
    }

    public static int addPlayersFromQueueSearch(Guild guild, Message message, PlayerSearchCriteria criteria) {
        String content = message.getContentRaw();
        List<Member> members = fetchMembersFromMessage(content, guild);
        List<String> existingIds = members.stream().map(Member::getId).toList();

        Duration hostWait = Duration.between(message.getTimeCreated().toInstant(), Instant.now());
        if (hostWait.isNegative()) hostWait = Duration.ZERO;
        List<String> addedIds = PlayerSearchService.get().searchAndAdd(criteria, existingIds, hostWait);

        List<Member> added = new ArrayList<>();
        for (String id : addedIds) {
            Member member = guild.getMemberById(id);
            if (member == null || members.contains(member)) continue;
            members.add(member);
            added.add(member);
        }
        if (added.isEmpty()) return 0;

        message.editMessage(regenerateLaunchMessage(content, members))
                .queue(Consumers.nop(), BotLogger::catchRestError);
        String mentions = added.stream().map(Member::getAsMention).collect(Collectors.joining(" & "));
        MessageHelper.sendMessageToChannel(
                message.getChannel(),
                mentions + (added.size() == 1 ? " was" : " were") + " added to the game from the matchmaking queue.");
        return added.size();
    }

    @ButtonHandler(value = "leaveGameList", save = false)
    public static void leaveGameList(ButtonInteractionEvent event) {
        List<Member> members = fetchMembersFromMessage(event);
        members.remove(event.getMember());
        event.getMessage()
                .editMessage(regenerateLaunchMessage(event.getMessage().getContentRaw(), members))
                .queue(Consumers.nop(), BotLogger::catchRestError);
        MessageHelper.sendMessageToEventChannel(event, event.getUser().getEffectiveName() + " left the game.");
        MatchmakingQueueSearchService.get().updateForRoster(event.getChannelId(), memberIds(members));
    }

    private static synchronized void createGameAndChannels(ButtonInteractionEvent event) {
        String userName = event.getUser().getEffectiveName();
        MessageHelper.sendMessageToEventChannel(event, userName + " pressed the [Create Game] button");

        if (!CreateGameService.isGameCreationAllowed()) {
            MessageHelper.sendMessageToChannel(
                    event.getMessageChannel(),
                    "Admins have temporarily turned off game creation, most likely to contain a bug. Please be patient.");
            return;
        }

        if (CreateGameService.isLockedFromCreatingGames(event)) {
            MessageHelper.sendMessageToChannel(
                    event.getMessageChannel(),
                    "You created a game within the last 10 minutes. Please wait or have someone else create it.");
            return;
        }

        String buttonMessage = event.getMessage().getContentRaw();

        List<Member> members = resolveMembers(event, buttonMessage);
        if (members.isEmpty()) {
            MessageHelper.sendMessageToChannel(event.getChannel(), "No valid members found.");
            return;
        }

        if (!validateMembersCanJoin(event, members)) {
            return;
        }

        Member gameOwner = members.isEmpty() ? null : members.getFirst();
        boolean isStaff = isStaff(event);

        if (!isStaff && !members.contains(event.getMember())) {
            MessageHelper.sendMessageToChannel(
                    event.getChannel(), "You must be a staff member or a member of the game to launch the game.");
            return;
        }

        if (!isStaff && isLikelyDoublePressedButton(members, event)) return;

        String gameName = CreateGameService.getNextPbdGameName();
        Category categoryChannel = resolveOrCreateCategory(gameName, event);
        if (categoryChannel == null) {
            resetPbdNumber(gameName);
            return;
        }

        event.getMessage().delete().queue(Consumers.nop(), BotLogger::catchRestError);
        MatchmakingQueueSearchService.get().remove(event.getChannelId());

        String gameSillyName = parseOrGenerateSillyName(buttonMessage);

        Game game = CreateGameService.createGameChannels(
                members, event, gameSillyName, gameName, gameOwner, categoryChannel);

        if (game == null) {
            resetPbdNumber(gameName);
            MessageHelper.sendMessageToEventChannel(event, "Something went wrong...");
            return;
        }

        removeLaunchedMembersFromMatchmakingQueue(members, gameName, event);

        MessageHelper.sendMessageToEventChannel(event, "Message for posterity:\n\n" + buttonMessage);
        GameManager.save(game, "Created game channels");
    }

    private static void removeLaunchedMembersFromMatchmakingQueue(
            List<Member> members, String gameName, ButtonInteractionEvent event) {
        List<Member> removed = new ArrayList<>();
        for (Member member : members) {
            if (MatchmakerService.get().leaveQueue(member.getId())) {
                removed.add(member);
                BotLogger.info("Removed user " + member.getId() + " from the matchmaking queue because they are in"
                        + " newly launched game " + gameName + ".");
            }
        }
        if (removed.isEmpty()) return;
        String mentions = removed.stream().map(Member::getAsMention).collect(Collectors.joining(" & "));
        MessageHelper.sendMessageToEventChannel(
                event,
                mentions + (removed.size() == 1 ? " was" : " were")
                        + " removed from the matchmaking queue because they are in this newly launched game.");
    }

    private static void resetPbdNumber(String gameName) {
        int pbdNumber = Integer.parseInt(gameName.replace("pbd", ""));
        GameManager.resetLatestPbdNumberFrom(pbdNumber);
    }

    private static String parseOrGenerateSillyName(String buttonMessage) {
        String gameSillyName = StringUtils.substringBetween(buttonMessage, "Game Fun Name: ", "\n");
        if (gameSillyName == null || gameSillyName.isEmpty()) {
            gameSillyName = CreateGameService.autoGenerateGameName();
        }
        return gameSillyName;
    }

    private static Category resolveOrCreateCategory(String gameName, ButtonInteractionEvent event) {
        String categoryChannelName = CreateGameService.getCategoryNameForGame(gameName);
        Category categoryChannel = null;
        List<Category> categories = CreateGameService.getAllAvailablePBDCategories();
        for (Category category : categories) {
            if (category.getName().toUpperCase().startsWith(categoryChannelName)) {
                categoryChannel = category;
                break;
            }
        }
        if (categoryChannel == null) categoryChannel = CreateGameService.createNewGameCategory(categoryChannelName);
        if (categoryChannel == null) {
            MessageHelper.sendMessageToEventChannel(
                    event,
                    "Could not automatically find a category that begins with **" + categoryChannelName
                            + "** - Please create this category.\n# Warning, this may mean all servers are at capacity.");
        }
        return categoryChannel;
    }

    private static boolean isStaff(Interaction event) {
        return CommandHelper.hasRole(event, JdaService.bothelperRoles)
                || CommandHelper.hasRole(event, JdaService.developerRoles);
    }

    private static boolean validateMembersCanJoin(ButtonInteractionEvent event, List<Member> members) {
        for (Member member : members) {
            if (!userCanJoinGame(event, member)) {
                return false;
            }
        }
        return true;
    }

    private static List<Member> resolveMembers(ButtonInteractionEvent event, String buttonMessage) {
        List<Member> members = fetchMembersFromMessage(event);
        if (!members.isEmpty()) {
            return members;
        }
        return parseMembersFromButtonMessage(event, buttonMessage);
    }

    private static List<Member> parseMembersFromButtonMessage(ButtonInteractionEvent event, String buttonMsg) {
        List<Member> members = new ArrayList<>();
        String[] parts = buttonMsg.split(":");

        for (int i = 3; i < parts.length; i++) {
            String userId = StringUtils.substringBefore(parts[i], ".");
            Member member = event.getGuild().getMemberById(userId);
            if (member != null) {
                members.add(member);
            }
        }
        return members;
    }

    private static boolean userCanJoinGame(ButtonInteractionEvent event, Member member) {
        if (member == null) return false;
        if (!member.getUser().isBot()
                && !CommandHelper.hasRole(event, JdaService.developerRoles)
                && !CommandHelper.hasRole(event, JdaService.bothelperRoles)) {
            ManagedPlayer managedPlayer = GameManager.getManagedPlayer(member.getId());
            int ongoingAmount = UserGameInfoService.countOngoingGamesThatAffectJoinLimit(managedPlayer);
            int completedGames = UserGameInfoService.countCompletedGamesThatAffectJoinLimit(managedPlayer);
            int limitIncrease = 0;
            if (event.getChannel() instanceof ThreadChannel channel) {
                String parentName = channel.getParentChannel().getName();
                if ("making-private-games".equalsIgnoreCase(parentName)) {
                    limitIncrease = 1;
                }
            }
            var userSettings = UserSettingsManager.get(member.getId());
            String trackRecord = userSettings.getTrackRecord();
            int droppedGames = 0;
            droppedGames -= StringUtils.countMatches(trackRecord, "replaced");
            droppedGames -= StringUtils.countMatches(trackRecord, "Dropped");
            limitIncrease += droppedGames;
            if (ongoingAmount > completedGames + 2 + limitIncrease && ongoingAmount != 0) {
                MessageHelper.sendMessageToChannel(
                        event.getChannel(),
                        member.getUser().getAsMention()
                                + " is at their game limit (# of ongoing games must be equal or less than # of completed games + 3 - dropped games) and so cannot join more games at the moment."
                                + " Their number of ongoing games is " + ongoingAmount
                                + ", their number of completed games is " + completedGames
                                + " and their number of dropped games is " + Math.abs(droppedGames) + ".\n\n"
                                + "If you're playing a private game with friends, you can ping a bothelper for a 1-game exemption from the limit.");
                return false;
            }
            if (ongoingAmount == completedGames + 2 + limitIncrease && ongoingAmount != 0) {
                MessageHelper.sendMessageToChannel(
                        event.getChannel(),
                        member.getUser().getAsMention()
                                + " this is a notice that you are now at your game limit. Your game limit is equal to your number of completed games + 3. If you are playing a private game with friends, you can ping a bothelper for a 1-game exemption from the limit. If you get replaced or drop any games, your limit decreases by 1.");
            }
            // Used for specific people we are limiting the amount of games of

            if (userSettings.getGameLimit() > 0 && ongoingAmount >= userSettings.getGameLimit()) {

                MessageHelper.sendMessageToChannel(
                        event.getChannel(),
                        member.getUser().getAsMention() + " is currently under a " + userSettings.getGameLimit()
                                + "-game limit and cannot join more games at this time");
                return false;
            }
        }
        return true;
    }

    private static boolean isLikelyDoublePressedButton(List<Member> members, ButtonInteractionEvent event) {
        String lastGameName = CreateGameService.getLastPbdGameName();
        if (lastGameName == null) return false;

        Game lastGame = GameManager.getManagedGame(lastGameName).getGame();
        for (Member member : members) {
            if (lastGame.getPlayerIDs().contains(member.getId())) {
                continue;
            }
            return false;
        }

        MessageHelper.sendMessageToChannel(
                event.getMessageChannel(),
                "The members of this game are identical to the members of the last game created, so the bot suspects a double press "
                        + "occurred and is cancelling the creation of another game. Have a bothelper press the button if this is incorrect.");
        return true;
    }
}
