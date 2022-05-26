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

import net.runelite.client.config.*;

@ConfigGroup("spamfilter")
public interface SpamFilterConfig extends Config
{
	@Units(Units.PERCENT)
	@ConfigItem(
		keyName = "threshold",
		name = "Threshold",
		description = "Lowering this will make the filter block more messages (at the cost of more false positives)"
	)
	default int threshold() {
		return 98;
	}

	@ConfigItem(
			keyName = "filtertype",
			name = "Filter type",
			description = "Filter type for chatbox"
	)
	default SpamFilterType filterType() {
		return SpamFilterType.HIDE_MESSAGES;
	}

	@ConfigItem(
			keyName = "showSpamScores",
			name = "Show spam scores",
			description = "Display spam scores in the chat.<br>" +
					"Each message's spam score is compared to the \"threshold\" setting.<br>" +
					"For example, messages with a spam score >= 0.99 will be considered spam when the threshold is 99%"
	)
	default boolean showSpamScores() {
		return false;
	}

	@ConfigItem(
			keyName = "filterOverheads",
			name = "Filter overheads",
			description = "Filter overhead messages"
	)
	default boolean filterOverheads() {
		return true;
	}

	@ConfigItem(
			keyName = "showMarkSpam",
			name = "Show \"Mark spam\" right-click option",
			description = "Allow chats to be marked as spam by right-clicking.<br>" +
					"This will raise the spam score of similar messages in the future",
			position = 1
	)
	default boolean showMarkSpam() {
		return true;
	}

	@ConfigItem(
			keyName = "showMarkHam",
			name = "Show \"Mark ham\" right-click option",
			description = "Allow chats to be marked as ham (not spam) by right-clicking.<br>" +
					"This will lower the spam score of similar messages in the future",
			position = 2
	)
	default boolean showMarkHam() {
		return false;
	}
}
