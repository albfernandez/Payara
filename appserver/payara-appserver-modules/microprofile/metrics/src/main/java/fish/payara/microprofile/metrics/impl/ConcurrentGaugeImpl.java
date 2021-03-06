/*
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 * 
 *    Copyright (c) [2019] Payara Foundation and/or its affiliates. All rights reserved.
 * 
 *     The contents of this file are subject to the terms of either the GNU
 *     General Public License Version 2 only ("GPL") or the Common Development
 *     and Distribution License("CDDL") (collectively, the "License").  You
 *     may not use this file except in compliance with the License.  You can
 *     obtain a copy of the License at
 *     https://github.com/payara/Payara/blob/master/LICENSE.txt
 *     See the License for the specific
 *     language governing permissions and limitations under the License.
 * 
 *     When distributing the software, include this License Header Notice in each
 *     file and include the License file at glassfish/legal/LICENSE.txt.
 * 
 *     GPL Classpath Exception:
 *     The Payara Foundation designates this particular file as subject to the "Classpath"
 *     exception as provided by the Payara Foundation in the GPL Version 2 section of the License
 *     file that accompanied this code.
 * 
 *     Modifications:
 *     If applicable, add the following below the License Header, with the fields
 *     enclosed by brackets [] replaced by your own identifying information:
 *     "Portions Copyright [year] [name of copyright owner]"
 * 
 *     Contributor(s):
 *     If you wish your version of this file to be governed by only the CDDL or
 *     only the GPL Version 2, indicate your decision by adding "[Contributor]
 *     elects to include this software in this distribution under the [CDDL or GPL
 *     Version 2] license."  If you don't indicate a single choice of license, a
 *     recipient has the option to distribute your version of this file under
 *     either the CDDL, the GPL Version 2 or to extend the choice of license to
 *     its licensees as provided above.  However, if you add GPL Version 2 code
 *     and therefore, elected the GPL Version 2 license, then the option applies
 *     only if the new code is made subject to such option by the copyright
 *     holder.
 *
 */

package fish.payara.microprofile.metrics.impl;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
import javax.enterprise.inject.Vetoed;
import org.eclipse.microprofile.metrics.ConcurrentGauge;

/**
 * Implementation of ConcurrentGauge from Microprofile Metrics
 * @see ConcurrentGauge
 * @since 5.193
 */
@Vetoed
public class ConcurrentGaugeImpl implements ConcurrentGauge {

    private final LongAdder count = new LongAdder();
    /**
     * Last point for which clearOld() has been run
     */
    private Instant lastInstant = Instant.now();
    
    /**
     * A map containing all the values that the count of the gauge has been at in the last minute.
     * The key is the time in seconds that the value changed, the value is the value of the count at the moment
     * before it changed
     */
    private Map<Instant, Long> lastCounts = new ConcurrentHashMap<>();
    
    /**
     * Increment the counter by one.
     */
    @Override
    public void inc() {
        clearOld();
        if (count.longValue() > 0) {
            lastCounts.put(Instant.now(), count.longValue());
        }
        count.increment();
    }

    /**
     * Returns the counter's current value.
     *
     * @return the counter's current value
     */
    @Override
    public long getCount() {
        return count.sum();
    }

    @Override
    public long getMax() {
        clearOld();
        long max = 0;
        Instant nowMinStart = Instant.now().truncatedTo(ChronoUnit.MINUTES);
        for (Map.Entry<Instant, Long> momentCount: lastCounts.entrySet()) {
            if (momentCount.getKey().isBefore(nowMinStart) && momentCount.getValue() > max) {
                max = momentCount.getValue();
            }
        }
        return max;
    }

    @Override
    public long getMin() {
        clearOld();
        long min = Long.MAX_VALUE;
        // if it was not called in the last minute, then the value is 0
        // but if it was called at least once in the last minute then the
        //value is non-zero, even if it was 0 at some point duing that minute
        if (lastCounts.isEmpty()) {
            return 0;
        }
        Instant nowMinStart = Instant.now().truncatedTo(ChronoUnit.MINUTES);
        for (Map.Entry<Instant, Long> momentCount: lastCounts.entrySet()) {
            if (momentCount.getKey().isBefore(nowMinStart) && momentCount.getValue() < min) {
                min = momentCount.getValue();
            }
        }
        return min;
    }

    @Override
    public void dec() {
        clearOld();
        lastCounts.put(Instant.now(), count.longValue());
        count.decrement();
    }
    
    /**
     * Removes counts that occured before the previous minute that finished
     */
    private void clearOld() {
        Instant previousMinute = Instant.now().truncatedTo(ChronoUnit.MINUTES).minus(1, ChronoUnit.MINUTES);
        if (previousMinute.equals(lastInstant)) {
            //already called in this minute
            return;
        }
        Iterator<Instant> guages = lastCounts.keySet().iterator();
        while (guages.hasNext()) {
            Instant guageTime = guages.next();
            if (guageTime.isBefore(previousMinute)) {
                lastCounts.remove(guageTime);
            }
        }
        lastInstant = previousMinute;
    }
}
