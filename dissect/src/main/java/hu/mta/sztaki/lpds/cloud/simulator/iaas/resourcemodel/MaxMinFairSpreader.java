/*
 *  ========================================================================
 *  DIScrete event baSed Energy Consumption simulaTor 
 *    					             for Clouds and Federations (DISSECT-CF)
 *  ========================================================================
 *  
 *  This file is part of DISSECT-CF.
 *  
 *  DISSECT-CF is free software: you can redistribute it and/or modify it
 *  under the terms of the GNU Lesser General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or (at
 *  your option) any later version.
 *  
 *  DISSECT-CF is distributed in the hope that it will be useful, but
 *  WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU Lesser
 *  General Public License for more details.
 *  
 *  You should have received a copy of the GNU Lesser General Public License
 *  along with DISSECT-CF.  If not, see <http://www.gnu.org/licenses/>.
 *  
 *  (C) Copyright 2014, Gabor Kecskemeti (gkecskem@dps.uibk.ac.at,
 *   									  kecskemeti.gabor@sztaki.mta.hu)
 */

package hu.mta.sztaki.lpds.cloud.simulator.iaas.resourcemodel;

public abstract class MaxMinFairSpreader extends ResourceSpreader {

	private double currentUnProcessed;
	private int unassignedNum;
	private int upLen;

	public MaxMinFairSpreader(final double perSecondProcessing) {
		super(perSecondProcessing);
	}

	private boolean initializeFreqUpdate() {
		unassignedNum = upLen = underProcessing.size();
		if (unassignedNum == 0) {
			return false;
		}
		for (int i = 0; i < upLen; i++) {
			final ResourceConsumption con = underProcessing.get(i);
			con.limithelper = 0;
			con.unassigned = true;
		}
		currentUnProcessed = perTickProcessingPower;
		return true;
	}

	private void assignProcessingPower() {
		if (currentUnProcessed > negligableProcessing && unassignedNum > 0) {
			int currlen = unassignedNum;
			for (int i = 0; i < upLen; i++) {
				ResourceConsumption con = underProcessing.get(i);
				con.inassginmentprocess = con.unassigned;
			}
			double currentProcessable = currentUnProcessed;
			double pastProcessable;
			int firstindex = 0;
			int lastindex = upLen;
			do {
				pastProcessable = currentProcessable;
				final double maxShare = currentProcessable / currlen;
				boolean firstIndexNotSetUp = true;
				int newlastindex = -1;
				for (int i = firstindex; i < lastindex; i++) {
					final ResourceConsumption con = underProcessing.get(i);
					if (con.inassginmentprocess) {
						final double limit = con.getProcessingLimit()
								- con.limithelper;
						if (limit < maxShare) {
							currentProcessable -= limit;
							updateConsumptionLimit(con, limit);
							// we move an unprocessed item from the back here
							// then allow reevaluation
							// and also make sure the currlen is reduced
							con.inassginmentprocess = false;
							currlen--;
						} else {
							newlastindex = i;
							if (firstIndexNotSetUp) {
								firstindex = i;
								firstIndexNotSetUp = false;
							}
							updateConsumptionLimit(con, maxShare);
						}
					}
				}
				lastindex = newlastindex;
			} while (currlen != 0 && pastProcessable != currentProcessable);
		}
	}

	@Override
	protected long singleGroupwiseFreqUpdater() {
		// Phase 1: preparation
		final ResourceSpreader.FreqSyncer syncer = getSyncer();
		final ResourceSpreader[] depgroup = syncer.getDepGroup();
		final int dglen = syncer.getDGLen();
		final int providerCount = syncer.getFirstConsumerId();
		for (int i = 0; i < dglen; i++) {
			((MaxMinFairSpreader) depgroup[i]).initializeFreqUpdate();
		}
		boolean someConsumptionIsStillUnderUtilized;
		// Phase 2: Progressive filling iteration
		do {
			// Phase 2a: determining maximum possible processing
			// Determining wishes for providers and consumers
			for (int i = 0; i < dglen; i++) {
				((MaxMinFairSpreader) depgroup[i]).assignProcessingPower();
			}
			// Phase 2b: Finding minimum between providers and consumers
			double minProcessing = Double.MAX_VALUE;
			for (int i = 0; i < providerCount; i++) {
				final int upLen = depgroup[i].underProcessing.size();
				for (int j = 0; j < upLen; j++) {
					final ResourceConsumption con = depgroup[i].underProcessing
							.get(j);
					if (con.unassigned) {
						final double currlimit = con.updateRealLimit();
						if (currlimit < minProcessing) {
							minProcessing = currlimit;
						}
					}
				}
			}

			// Phase 2c: single filling
			someConsumptionIsStillUnderUtilized = false;
			for (int i = 0; i < providerCount; i++) {
				MaxMinFairSpreader mmfs = (MaxMinFairSpreader) depgroup[i];
				for (int j = 0; j < mmfs.upLen; j++) {
					final ResourceConsumption con = mmfs.underProcessing.get(j);
					if (con.unassigned) {
						con.limithelper += minProcessing;
						final MaxMinFairSpreader counterpart = (MaxMinFairSpreader) mmfs
								.getCounterPart(con);
						mmfs.currentUnProcessed -= minProcessing;
						counterpart.currentUnProcessed -= minProcessing;
						if (con.getRealLimitPerSecond() == minProcessing) {
							con.unassigned = false;
							mmfs.unassignedNum--;
							counterpart.unassignedNum--;
						}
					}
				}
				someConsumptionIsStillUnderUtilized |= mmfs.unassignedNum > 0;
			}
		} while (someConsumptionIsStillUnderUtilized);
		// Phase 3: Determining the earliest completion time
		long minCompletionDistance = Long.MAX_VALUE;
		for (int i = 0; i < providerCount; i++) {
			final int upLen = depgroup[i].underProcessing.size();
			for (int j = 0; j < upLen; j++) {
				final ResourceConsumption con = depgroup[i].underProcessing
						.get(j);
				con.consumerLimit = con.providerLimit = con.limithelper;
				con.updateRealLimit();
				final long conDistance = con.getCompletionDistance();
				minCompletionDistance = conDistance < minCompletionDistance ? conDistance
						: minCompletionDistance;
			}
		}
		return minCompletionDistance;
	}

	protected abstract void updateConsumptionLimit(
			final ResourceConsumption con, final double limit);
}
