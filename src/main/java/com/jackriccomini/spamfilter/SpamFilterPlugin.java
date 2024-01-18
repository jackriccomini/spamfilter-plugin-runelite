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
import net.runelite.api.*;
import net.runelite.api.events.*;
import net.runelite.api.widgets.Widget;
import net.runelite.api.widgets.WidgetInfo;
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
	public void onMenuOpened(MenuOpened event) {
		MenuEntry[] entries = event.getMenuEntries();
		// take second entry since the first may be something like "walk here" if chatbox is transparent
		MenuEntry secondEntry = entries[entries.length - 2];

		int clickedWidgetId = secondEntry.getParam1();
		Widget widget = client.getWidget(clickedWidgetId);
		if (widget == null) {
			return;
		}

		// did user click on a chat?
		if (widget.getParentId() != WidgetInfo.CHATBOX_MESSAGE_LINES.getId()) {
			return;
		}

		// As far as I can tell by skimming the builtin chat history and hiscores plugins:
		// Click doesn't happen on a chat message, it happens on the *sender* of the chat message.
		// There is a static list of senders. First static child is the most recent sender,
		// Second static child is second most recent sender, and so on.
		// Chat messages are dynamic children of CHATBOX_MESSAGES_LINES.
		int firstChatSender = WidgetInfo.CHATBOX_FIRST_MESSAGE.getChildId();
		int clickedChatSender = WidgetInfo.TO_CHILD(clickedWidgetId);
		int clickOffset = clickedChatSender - firstChatSender;
		// can calculate the offset between "clicked-on chat message" and "most recent chat message"
		// by looking at the offset between "clicked-on chat sender" and "most recent chat sender"
		int selectedChatOffset = (clickOffset * 4) + 1;

		Widget selectedChatWidget = widget.getParent().getChild(selectedChatOffset);
		if (selectedChatWidget == null) {
			return;
		}
		String selectedChat = Text.removeTags(selectedChatWidget.getText());
		if (config.showMarkSpam()) {
			client.createMenuEntry(1)
					.setOption("Mark spam")
					.setType(MenuAction.RUNELITE)
					.setTarget(ColorUtil.wrapWithColorTag("message", Color.WHITE))
					.onClick(e -> {
						markSpam(selectedChat);
						client.refreshChat();
					});
		}
		if (config.showMarkHam()) {
			client.createMenuEntry(1)
					.setOption("Mark ham")
					.setType(MenuAction.RUNELITE)
					.setTarget(ColorUtil.wrapWithColorTag("message", Color.WHITE))
					.onClick(e -> {
						markHam(selectedChat);
						client.refreshChat();
					});
		}
	}

	@Subscribe
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
		String[] stringStack = client.getStringStack();
		int stringStackSize = client.getStringStackSize();

		final int messageType = intStack[intStackSize - 2];
		final int messageId = intStack[intStackSize - 1];
		String message = stringStack[stringStackSize - 1];
		ChatMessageType chatMessageType = ChatMessageType.of(messageType);
		if (chatMessageType != ChatMessageType.PUBLICCHAT) {
			return;
		}
		final MessageNode messageNode = client.getMessages().get(messageId);

		// Disable spam filtering for the player's own messages
		if (messageNode != null) {
			final String senderName = Text.removeTags(messageNode.getName());
			final String displayName = client.getLocalPlayer().getName();
			if (senderName.equalsIgnoreCase(displayName)) {
				return;
			}
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
			message = message + " " + spamRatingDisplay;
		}
		stringStack[stringStackSize - 1] = message;

	}

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

	float pMessageBad(String message) {
		String msg = message.toLowerCase();
		String[] tokens = msg.split("\\s+");
		if (tokens.length == 1 && !message.startsWith("!")) {
			// single-word messages easily induce false positives so we ignore them.
			// however, messages starting with "!" are still processed since they are often commands
			// for gambling bots (e.g. "!w")
			return 0.0f;
		}
		Set<String> tokensUnique = new HashSet<>();
		for (String token : tokens) {
			tokensUnique.add(token);
		}
		float pPredictorsCorrect = 1f;
		float pPredictorsIncorrect = 1f;
		for (String token : tokensUnique) {
			float p = pTokenBad(token);
			pPredictorsCorrect *= p;
			pPredictorsIncorrect *= (1 - p);
		}
		return pPredictorsCorrect / (pPredictorsCorrect + pPredictorsIncorrect);
	}

	HashMap<String, Integer> countTokens(List<String>... corpuses) {
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
