/*
 * Copyright (c) 2022 Jack Riccomini
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package com.jackriccomini.spamfilter;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.*;

import com.google.inject.Provides;
import javax.inject.Inject;
import java.lang.Math;
import java.awt.Color;
import java.text.DecimalFormat;
import java.util.stream.Collectors;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.MenuAction;
import net.runelite.api.Player;
import net.runelite.api.MessageNode;
import net.runelite.api.ChatMessageType;
import net.runelite.api.events.MenuEntryAdded;
import net.runelite.api.events.OverheadTextChanged;
import net.runelite.api.events.ScriptCallbackEvent;
import net.runelite.api.widgets.Widget;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.WidgetUtil;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.util.ColorUtil;
import net.runelite.client.util.Text;
import net.runelite.client.RuneLite;

@Slf4j
@PluginDescriptor(
	name = "Spam filter"
)
public class SpamFilterPlugin extends Plugin
{
	// Note on variable/method naming: read pFoo as "probability of foo",
	// e.g. "pMessageBad" = "probability of the message being bad"

	@Inject
	private Client client;

	@Inject
	private SpamFilterConfig config;

	File configDir;
	File userGoodCorpusFile;
	File userBadCorpusFile;

	// built-in corpuses
	private List<String> goodCorpus;
	private List<String> badCorpus;

	// corpuses resulting from user selecting "mark spam" and "mark ham"
	private List<String> userGoodCorpus;
	private List<String> userBadCorpus;

	private Map<String, Integer> goodCounts;
	private Map<String, Integer> badCounts;

	private static final String REPORT = "Report";

	@Override
	protected void startUp() throws Exception {
		configDir = new File(RuneLite.RUNELITE_DIR, "spam-filter");
		userGoodCorpusFile = new File(configDir, "user_good_corpus.txt");
		userBadCorpusFile = new File(configDir, "user_bad_corpus.txt");
		if (configDir.mkdir()) {
			log.info("Made spam-filter directory");
			boolean good = userGoodCorpusFile.createNewFile();
			boolean bad = userBadCorpusFile.createNewFile();
			assert good && bad;
		}

		InputStream goodCorpusRes = this.getClass().getResourceAsStream("/good_corpus.txt");
		InputStream badCorpusRes = this.getClass().getResourceAsStream("/bad_corpus.txt");
		BufferedReader goodCorpusReader = new BufferedReader(new InputStreamReader(goodCorpusRes, StandardCharsets.UTF_8));
		BufferedReader badCorpusReader = new BufferedReader(new InputStreamReader(badCorpusRes, StandardCharsets.UTF_8));
		goodCorpus = goodCorpusReader.lines().collect(Collectors.toList());
		badCorpus = badCorpusReader.lines().collect(Collectors.toList());
		goodCorpusReader.close();
		badCorpusReader.close();

		userGoodCorpus = Files.readAllLines(userGoodCorpusFile.toPath());
		userBadCorpus = Files.readAllLines(userBadCorpusFile.toPath());
		log.info("Loaded built-in corpus files with " + goodCorpus.size() + " (g) & " + badCorpus.size() + " (b) entries");
		log.info("Loaded user corpus files with " + userGoodCorpus.size() + " (g) & " + userBadCorpus.size() + " (b) entries");
		generateTokenCounts();
	}

	private void appendToUserCorpus(String message, List<String> corpus, File corpusFile) {
		if (config.showSpamScores()) {
			// strip the " (0.xx)" spam score which we may have appended.
			// there is a race condition - this won't work right if "show spam scores" is toggled and then
			// a chat line is marked ham/spam before the "chatFilterCheck" event has fired.
			// wontfix for now since it won't crash, the chat line will just be wrong
			message = message.substring(0, message.lastIndexOf("(") - 1);
		}
		corpus.add(message);
		generateTokenCounts();
		try {
			Files.write(corpusFile.toPath(), corpus, StandardOpenOption.TRUNCATE_EXISTING);
		} catch (Exception e) {
			log.warn("Something went wrong writing a corpus file", e);
		}
	}

	@Provides
	SpamFilterConfig provideConfig(ConfigManager configManager) {
		return configManager.getConfig(SpamFilterConfig.class);
	}

	private void generateTokenCounts() {
		goodCounts = countTokens(goodCorpus, userGoodCorpus);
		badCounts = countTokens(badCorpus, userBadCorpus);
	}

	private void markSpam(String chatLine) {
		appendToUserCorpus(chatLine, userBadCorpus, userBadCorpusFile);
	}

	private void markHam(String chatLine) {
		appendToUserCorpus(chatLine, userGoodCorpus, userGoodCorpusFile);
	}

	@Subscribe
	public void onMenuEntryAdded(MenuEntryAdded entry) {

		// Report is always in the chatbox, this is a way to only add "mark ham/spam" once instead of 3+ times
		if (!entry.getOption().equals(REPORT)) {
			return;
		}

		if (entry.getType() != MenuAction.CC_OP.getId() && entry.getType() != MenuAction.CC_OP_LOW_PRIORITY.getId()) {
			return;
		}

		final int groupId = WidgetUtil.componentToInterface(entry.getActionParam1());
		final int childId = WidgetUtil.componentToId(entry.getActionParam1());

		// did user click on a chat?
		if (groupId!= InterfaceID.CHATBOX) {
			return;
		}

		final Widget widget = client.getWidget(groupId, childId);
		final Widget parent = widget.getParent();

		if (InterfaceID.Chatbox.SCROLLAREA != parent.getId()) {
			return;
		}

		// As far as I can tell by skimming the builtin chat history and hiscores plugins:
		// Convert current message static widget id to dynamic widget id of message node with message contents
		// When message is right-clicked, we are actually right-clicking static widget that contains only sender.
		// The actual message contents are stored in dynamic widgets that follow same order as static widgets.
		// Every first dynamic widget is message sender, every second one is message contents,
		// every third one is clan name and every fourth one is clan rank icon.
		// The last two are hidden when the message is not from a clan chat or guest clan chat.
		int firstChatSender = WidgetUtil.componentToId(InterfaceID.Chatbox.LINE0);
		int clickOffset = childId - firstChatSender;
		// can calculate the offset between "clicked-on chat message" and "most recent chat message"
		// by looking at the offset between "clicked-on chat sender" and "most recent chat sender"
		int selectedChatOffset = (clickOffset * 4) + 1;

		Widget selectedChatWidget = parent.getChild(selectedChatOffset);
		if (selectedChatWidget == null) {
			return;
		}
		String selectedChat = Text.removeTags(selectedChatWidget.getText());

		if (config.showMarkSpam()) {
			client.getMenu().createMenuEntry(1)
					.setOption("Mark spam")
					.setType(MenuAction.RUNELITE)
					.setTarget(ColorUtil.wrapWithColorTag("message", Color.WHITE))
					.onClick(e -> {
						markSpam(selectedChat);
						client.refreshChat();
					});
		}

		if (config.showMarkHam()) {
			client.getMenu().createMenuEntry(1)
					.setOption("Mark ham")
					.setType(MenuAction.RUNELITE)
					.setTarget(ColorUtil.wrapWithColorTag("message", Color.WHITE))
					.onClick(e -> {
						markHam(selectedChat);
						client.refreshChat();
					});
		}
	}

	@Subscribe(priority = -1) // run after builtin Chat Filter plugin
	public void onOverheadTextChanged(OverheadTextChanged event) {
		final String displayName = client.getLocalPlayer().getName();
		final String senderName = event.getActor().getName();
		if (!(event.getActor() instanceof Player) ||
				!config.filterOverheads() ||
				// Disable spam filtering for the player's own messages
				(displayName != null && displayName.equalsIgnoreCase(senderName))
		) {
			return;
		}
		String message = event.getOverheadText();

		// Overhead text will already have leading and trailing whitespace stripped
		// (as opposed to chatbox messages, which will not) but I'm not sure which characters
		// the game counts as whitespace when it does this.
		// Since we .strip() chatbox messages, .strip() overhead messages too for consistency
		float spamRating = pMessageBad(message.strip());
		boolean isSpam = spamRating > ((float) config.threshold() / 100);
		if (isSpam) {
			event.getActor().setOverheadText(" ");
		}
	}

	@Subscribe
	public void onScriptCallbackEvent(ScriptCallbackEvent event) {
		if (!event.getEventName().equals("chatFilterCheck")) {
			return;
		}

		int[] intStack = client.getIntStack();
		int intStackSize = client.getIntStackSize();
		Object[] objectStack = client.getObjectStack();
		int objectStackSize = client.getObjectStackSize();

		final int messageType = intStack[intStackSize - 2];
		final int messageId = intStack[intStackSize - 1];
		String message = (String) objectStack[objectStackSize - 1];

		ChatMessageType chatMessageType = ChatMessageType.of(messageType);
		if (chatMessageType != ChatMessageType.PUBLICCHAT) {
			return;
		}
		final MessageNode messageNode = client.getMessages().get(messageId);

		// Disable spam filtering for the player's own messages
		if (isCurrPlayersMsg(messageNode)) {
			return;
		}

		// Overhead message strings already have leading and trailing whitespace stripped but chatbox message strings
		// do not. If we don't strip whitespace then we will tokenise overhead vs chatbox messages inconsistently and
		// potentially assign different spam scores to the same message viewed in overhead vs chatbox
		// e.g. https://github.com/jackriccomini/spamfilter-plugin-runelite/issues/10
		float spamRating = pMessageBad(message.strip());
		boolean isSpam = spamRating > ((float) config.threshold() / 100);

		if (isSpam) {
			if (config.filterType() == SpamFilterType.HIDE_MESSAGES) {
				intStack[intStackSize - 3] = 0;
			} else if (config.filterType() == SpamFilterType.GREY_MESSAGES){
				message = ColorUtil.wrapWithColorTag(message, Color.GRAY);
			}
		}

		if (config.showSpamScores()) {
			DecimalFormat df = new DecimalFormat("0.00");
			String spamRatingDisplay = ColorUtil.wrapWithColorTag("(" + df.format(spamRating) + ")", Color.MAGENTA);
			message += " " + spamRatingDisplay;
		}

		objectStack[objectStackSize - 1] = message;

	}

	/**
	 * Determines if the chat message being processed is sent by the currently
	 * logged-in player. Used to disable the chat filter for the player's own messages.
	 *
	 * @param messageNode The MessageNode that contains information about the chat message.
	 * @return boolean true if the message is sent by the player. Otherwise, false.
	 */
	private boolean isCurrPlayersMsg(MessageNode messageNode) {
		if (messageNode != null) {
			final String senderName = Text.removeTags(messageNode.getName());
			final String displayName = client.getLocalPlayer().getName();

			if (displayName == null) {
				return true;
			}

			// Remove potential differences in encoding of the space character
			final String normalizedSenderName = senderName.replaceAll("\\h", " ");
			final String normalizedDisplayName = displayName.replaceAll("\\h", " ");
			return normalizedSenderName.equalsIgnoreCase(normalizedDisplayName);
		}
		return false;
	}

	/**
	 * Calculates the probability that a given token is spam based on observed "good" and "bad" message counts.
	 * <p>
	 * The method looks up how many times the token has appeared in both good and bad messages,
	 * and computes a naive probability:
	 * <pre>
	 *   badCount / (goodCount + badCount)
	 * </pre>
	 * If the token has never been seen before, it defaults to a neutral probability of 0.4.
	 * The result is then clamped to the range [0.01, 0.99] to prevent extreme values that
	 * could skew downstream calculations too heavily.
	 *
	 * @param token the text token (word) to evaluate
	 * @return the spam probability of the token, between 0.01 and 0.99
	 */
	float pTokenBad(String token) {
		int goodCount = goodCounts.getOrDefault(token, 0);
		int badCount = badCounts.getOrDefault(token, 0);
		if (goodCount + badCount == 0) {
			return 0.4f;
		}
		float rawProbability = (float) badCount / (float) (goodCount + badCount);
		float clampUpperBound = Math.min(rawProbability, 0.99f);
		float clampLowerBound = Math.max(clampUpperBound, 0.01f);
		return clampLowerBound;
	}

	/**
	 * Estimates the probability that a given message is spam based on its token composition using a naive Bayesian approach.
	 * <p>
	 * The message is lowercased and split into whitespace-separated tokens. Single-word messages (excluding those starting with "!")
	 * are ignored to reduce false positives, as they are often ambiguous.
	 * <p>
	 * Each unique token is evaluated using {@link #pTokenBad(String)} to get its spam probability. These are combined
	 * using naive Bayes multiplication to compute:
	 * <ul>
	 *   <li>{@code pPredictorsCorrect} — the joint probability that all tokens are spam indicators</li>
	 *   <li>{@code pPredictorsIncorrect} — the joint probability that all tokens are not spam indicators</li>
	 * </ul>
	 * The final score is calculated using:
	 * <pre>
	 *   pSpam = pPredictorsCorrect / (pPredictorsCorrect + pPredictorsIncorrect)
	 * </pre>
	 * This produces a value between 0.0 and 1.0 indicating how likely the message is to be spam.
	 *
	 * @param message the full message text to evaluate
	 * @return the calculated spam probability between 0.0 and 1.0
	 */
	float pMessageBad(String message) {
		String msg = message.toLowerCase();
		String[] tokens = msg.split("\\s+");
		if (tokens.length == 1 && !message.startsWith("!")) {
			// single-word messages easily induce false positives so we ignore them.
			// however, messages starting with "!" are still processed since they are often commands
			// for gambling bots (e.g. "!w")
			return 0.0f;
		}
        Set<String> tokensUnique = new HashSet<>(Arrays.asList(tokens));
		float pPredictorsCorrect = 1f;
		float pPredictorsIncorrect = 1f;
		for (String token : tokensUnique) {
			float p = pTokenBad(token);
			pPredictorsCorrect *= p;
			pPredictorsIncorrect *= (1 - p);
		}
		return pPredictorsCorrect / (pPredictorsCorrect + pPredictorsIncorrect);
	}

	/**
	 * Counts the frequency of each token (word) across one or more lists of messages (corpuses).
	 * <p>
	 * Each message is converted to lowercase and split on whitespace. The resulting tokens are aggregated
	 * into a single {@link HashMap}, where each key is a token and the value is the number of times
	 * it appears across all provided corpuses.
	 * <p>
	 * This method is marked with {@code @SafeVarargs} because it accepts a varargs parameter of generic types,
	 * and it guarantees that no heap pollution will occur during its execution.
	 *
	 * @param corpuses one or more lists of messages, each representing a corpus
	 * @return a map of token strings to their total counts across all corpuses
	 */
	@SafeVarargs
    final HashMap<String, Integer> countTokens(List<String>... corpuses) {
		HashMap<String, Integer> counts = new HashMap<>();
		for (List<String> corpus : corpuses) {
			for (String message : corpus) {
				message = message.toLowerCase();
				String[] tokens = message.split("\\s");
				for (String token : tokens) {
					counts.put(token, counts.getOrDefault(token, 0) + 1);
				}
			}
		}
		return counts;
	}
}
