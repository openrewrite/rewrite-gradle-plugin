/*
 * Copyright ${year} the original author or authors.
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * https://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.openrewrite.gradle;

import java.time.Duration;

public class TimeUtils {
    private TimeUtils() {
    }
    public static final int MINUTES_PER_HOUR = 60;
    public static final int SECONDS_PER_MINUTE = 60;
    public static final int SECONDS_PER_HOUR = SECONDS_PER_MINUTE * MINUTES_PER_HOUR;
    public static final int SECONDS_PER_DAY = SECONDS_PER_HOUR * 24;

    public static String prettyPrint(Duration duration) {
        StringBuilder result = new StringBuilder();
        long days = duration.getSeconds() / SECONDS_PER_DAY;
        boolean startedPrinting = false;
        if (days > 0) {
            startedPrinting = true;
            result.append(days);
            result.append(" day");
            if (days != 1) {
                result.append("s");
            }
            result.append(" ");
        }

        long hours =  duration.toHours() % 24;
        if (startedPrinting || hours > 0) {
            startedPrinting = true;
            result.append(hours);
            result.append(" hour");
            if (hours != 1) {
                result.append("s");
            }
            result.append(" ");
        }

        long minutes = (duration.getSeconds() / SECONDS_PER_MINUTE) % MINUTES_PER_HOUR;
        if (startedPrinting || minutes > 0) {
            result.append(minutes);
            result.append(" minute");
            if (minutes != 1) {
                result.append("s");
            }
            result.append(" ");
        }

        long seconds = duration.getSeconds() % SECONDS_PER_MINUTE;
        if (startedPrinting || seconds > 0) {
            result.append(seconds);
            result.append(" second");
            if (seconds != 1) {
                result.append("s");
            }
            result.append(" ");
        }

        long millis = duration.getNano() / 1000_000;
        result.append(millis);
        result.append(" millisecond");
        if (millis != 1) {
            result.append("s");
        }

        return result.toString();
    }
}
