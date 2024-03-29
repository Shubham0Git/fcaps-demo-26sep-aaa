/*
 * Copyright 2017-present Open Networking Foundation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package org.opencord.aaa.impl;

import static org.slf4j.LoggerFactory.getLogger;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.onlab.packet.MacAddress;
import org.onosproject.net.ConnectPoint;
import org.opencord.aaa.AuthenticationEvent;
import org.opencord.aaa.StateMachineDelegate;
import org.slf4j.Logger;

import com.google.common.collect.Maps;

/**
 * AAA Finite State Machine.
 */

class StateMachine {
    // INDEX to identify the state in the transition table
    static final int STATE_IDLE = 0;
    static final int STATE_STARTED = 1;
    static final int STATE_PENDING = 2;
    static final int STATE_AUTHORIZED = 3;
    static final int STATE_UNAUTHORIZED = 4;

    // Defining the states where timeout can happen
    static final Set<Integer> TIMEOUT_ELIGIBLE_STATES = new HashSet();
    static {
        TIMEOUT_ELIGIBLE_STATES.add(STATE_STARTED);
        TIMEOUT_ELIGIBLE_STATES.add(STATE_PENDING);
    }
    // INDEX to identify the transition in the transition table
    static final int TRANSITION_START = 0; // --> started
    static final int TRANSITION_REQUEST_ACCESS = 1;
    static final int TRANSITION_AUTHORIZE_ACCESS = 2;
    static final int TRANSITION_DENY_ACCESS = 3;
    static final int TRANSITION_LOGOFF = 4;

    private static int identifier = 1;
    private byte challengeIdentifier;
    private byte[] challengeState;
    private byte[] username;
    private byte[] requestAuthenticator;

    // Supplicant connectivity info
    private ConnectPoint supplicantConnectpoint;
    private MacAddress supplicantAddress;
    private short vlanId;
    private byte priorityCode;

    // Boolean flag indicating whether response is pending from AAA Server.
    // Used for counting timeout happening for AAA Sessions due to no response.
    private boolean waitingForRadiusResponse;

    private static int cleanupTimerTimeOutInMins;

    private String sessionId = null;

    private final Logger log = getLogger(getClass());

    private State[] states = {new Idle(), new Started(), new Pending(), new Authorized(), new Unauthorized() };

    // Cleanup Timer instance created for this session
    private java.util.concurrent.ScheduledFuture<?> cleanupTimer = null;

    // TimeStamp of last EAPOL or RADIUS message received.
    private long lastPacketReceivedTime = 0;

    // State transition table
    /*
     *
     * state IDLE | STARTED | PENDING | AUTHORIZED | UNAUTHORIZED //// input
     * -----------------------------------------------------------------------------
     * -----------------------
     *
     * START STARTED | _ | _ | STARTED | STARTED
     *
     * REQUEST_ACCESS _ | PENDING | _ | _ | _
     *
     * AUTHORIZE_ACCESS _ | _ | AUTHORIZED | _ | _
     *
     * DENY_ACCESS _ | - | UNAUTHORIZED | _ | _
     *
     * LOGOFF _ | _ | _ | IDLE | IDLE
     */

    private int[] idleTransition = {STATE_STARTED, STATE_IDLE, STATE_IDLE, STATE_IDLE, STATE_IDLE };
    private int[] startedTransition = {STATE_STARTED, STATE_PENDING, STATE_STARTED, STATE_STARTED, STATE_STARTED };
    private int[] pendingTransition = {STATE_PENDING, STATE_PENDING, STATE_AUTHORIZED, STATE_UNAUTHORIZED,
            STATE_PENDING };
    private int[] authorizedTransition = {STATE_STARTED, STATE_AUTHORIZED, STATE_AUTHORIZED, STATE_AUTHORIZED,
            STATE_IDLE };
    private int[] unauthorizedTransition = {STATE_STARTED, STATE_UNAUTHORIZED, STATE_UNAUTHORIZED, STATE_UNAUTHORIZED,
            STATE_IDLE };

    // THE TRANSITION TABLE
    private int[][] transition = {idleTransition, startedTransition, pendingTransition, authorizedTransition,
            unauthorizedTransition };

    private int currentState = STATE_IDLE;

    // Maps of state machines. Each state machine is represented by an
    // unique identifier on the switch: dpid + port number
    private static Map<String, StateMachine> sessionIdMap;
    private static Map<Integer, StateMachine> identifierMap;

    private static StateMachineDelegate delegate;

    public static void initializeMaps() {
        sessionIdMap = Maps.newConcurrentMap();
        identifierMap = Maps.newConcurrentMap();
        identifier = 1;
    }

    public static void destroyMaps() {
        sessionIdMap = null;
        identifierMap = null;
    }

    public static void setDelegate(StateMachineDelegate delegate) {
        StateMachine.delegate = delegate;
    }

    public static void setcleanupTimerTimeOutInMins(int cleanupTimerTimeoutInMins) {
        cleanupTimerTimeOutInMins = cleanupTimerTimeoutInMins;
    }

    public static void unsetDelegate(StateMachineDelegate delegate) {
        if (StateMachine.delegate == delegate) {
            StateMachine.delegate = null;
        }
    }

    public static Map<String, StateMachine> sessionIdMap() {
        return sessionIdMap;
    }

    public static StateMachine lookupStateMachineById(byte identifier) {
        return identifierMap.get((int) identifier);
    }

    public static StateMachine lookupStateMachineBySessionId(String sessionId) {
        return sessionIdMap.get(sessionId);
    }

    public static void deleteStateMachineId(String sessionId) {
        sessionIdMap.remove(sessionId);
    }

    public static void deleteStateMachineMapping(StateMachine machine) {
        identifierMap.entrySet().removeIf(e -> e.getValue().equals(machine));
        if (machine.cleanupTimer != null) {
            machine.cleanupTimer.cancel(false);
            machine.cleanupTimer = null;
        }
    }

    public java.util.concurrent.ScheduledFuture<?> getCleanupTimer() {
        return cleanupTimer;
    }

    public boolean isWaitingForRadiusResponse() {
        return waitingForRadiusResponse;
    }

    public void setWaitingForRadiusResponse(boolean waitingForRadiusResponse) {
        this.waitingForRadiusResponse = waitingForRadiusResponse;
    }

    public void setCleanupTimer(java.util.concurrent.ScheduledFuture<?> cleanupTimer) {
        this.cleanupTimer = cleanupTimer;
    }

    /**
     * Deletes authentication state machine records for a given MAC address.
     *
     * @param mac mac address of the suppliant who's state machine should be removed
     */
    public static void deleteByMac(MacAddress mac) {

        // Walk the map from session IDs to state machines looking for a MAC match
        for (Map.Entry<String, StateMachine> e : sessionIdMap.entrySet()) {

            // If a MAC match is found then delete the entry from the session ID
            // and identifier map as well as call delete identifier to clean up
            // the identifier bit set.
            if (e.getValue() != null && e.getValue().supplicantAddress != null
                    && e.getValue().supplicantAddress.equals(mac)) {
                sessionIdMap.remove(e.getValue().sessionId);
                if (e.getValue().identifier != 1) {
                    deleteStateMachineMapping(e.getValue());
                }
                break;
            }
        }
    }

    /**
     * Creates a new StateMachine with the given session ID.
     *
     * @param sessionId session Id represented by the switch dpid + port number
     */
    public StateMachine(String sessionId) {
        log.info("Creating a new state machine for {}", sessionId);
        this.sessionId = sessionId;
        sessionIdMap.put(sessionId, this);
    }

    /**
     * Gets the connect point for the supplicant side.
     *
     * @return supplicant connect point
     */
    public ConnectPoint supplicantConnectpoint() {
        return supplicantConnectpoint;
    }

    /**
     * Sets the supplicant side connect point.
     *
     * @param supplicantConnectpoint supplicant select point.
     */
    public void setSupplicantConnectpoint(ConnectPoint supplicantConnectpoint) {
        this.supplicantConnectpoint = supplicantConnectpoint;
    }

    /**
     * Gets the MAC address of the supplicant.
     *
     * @return supplicant MAC address
     */
    public MacAddress supplicantAddress() {
        return supplicantAddress;
    }

    /**
     * Sets the supplicant MAC address.
     *
     * @param supplicantAddress new supplicant MAC address
     */
    public void setSupplicantAddress(MacAddress supplicantAddress) {
        this.supplicantAddress = supplicantAddress;
    }

    /**
     * Sets the lastPacketReceivedTime.
     *
     * @param lastPacketReceivedTime timelastPacket was received
     */
    public void setLastPacketReceivedTime(long lastPacketReceivedTime) {
        this.lastPacketReceivedTime = lastPacketReceivedTime;
    }

    /**
     * Gets the lastPacketReceivedTime.
     *
     * @return lastPacketReceivedTime
     */
    public long getLastPacketReceivedTime() {
        return lastPacketReceivedTime;
    }

    /**
     * Gets the client's Vlan ID.
     *
     * @return client vlan ID
     */
    public short vlanId() {
        return vlanId;
    }

    /**
     * Sets the client's vlan ID.
     *
     * @param vlanId new client vlan ID
     */
    public void setVlanId(short vlanId) {
        this.vlanId = vlanId;
    }

    /**
     * Gets the client's priority Code.
     *
     * @return client Priority code
     */
    public byte priorityCode() {
        return priorityCode;
    }

    /**
     * Sets the client's priority Code.
     *
     * @param priorityCode new client priority Code
     */
    public void setPriorityCode(byte priorityCode) {
        this.priorityCode = priorityCode;
    }

    /**
     * Gets the client id that is requesting for access.
     *
     * @return The client id.
     */
    public String sessionId() {
        return this.sessionId;
    }

    /**
     * Set the challenge identifier and the state issued by the RADIUS.
     *
     * @param challengeIdentifier The challenge identifier set into the EAP packet
     *                            from the RADIUS message.
     * @param challengeState      The challenge state from the RADIUS.
     */
    protected void setChallengeInfo(byte challengeIdentifier, byte[] challengeState) {
        this.challengeIdentifier = challengeIdentifier;
        this.challengeState = challengeState;
    }

    /**
     * Set the challenge identifier issued by the RADIUS on the access challenge
     * request.
     *
     * @param challengeIdentifier The challenge identifier set into the EAP packet
     *                            from the RADIUS message.
     */
    protected void setChallengeIdentifier(byte challengeIdentifier) {
        log.info("Set Challenge Identifier to {}", challengeIdentifier);
        this.challengeIdentifier = challengeIdentifier;
    }

    /**
     * Gets the challenge EAP identifier set by the RADIUS.
     *
     * @return The challenge EAP identifier.
     */
    protected byte challengeIdentifier() {
        return this.challengeIdentifier;
    }

    /**
     * Set the challenge state info issued by the RADIUS.
     *
     * @param challengeState The challenge state from the RADIUS.
     */
    protected void setChallengeState(byte[] challengeState) {
        log.info("Set Challenge State");
        this.challengeState = challengeState;
    }

    /**
     * Gets the challenge state set by the RADIUS.
     *
     * @return The challenge state.
     */
    protected byte[] challengeState() {
        return this.challengeState;
    }

    /**
     * Set the username.
     *
     * @param username The username sent to the RADIUS upon access request.
     */
    protected void setUsername(byte[] username) {
        this.username = username;
    }

    /**
     * Gets the username.
     *
     * @return The requestAuthenticator.
     */
    protected byte[] requestAuthenticator() {
        return this.requestAuthenticator;
    }

    /**
     * Sets the authenticator.
     *
     * @param authenticator The username sent to the RADIUS upon access request.
     */
    protected void setRequestAuthenticator(byte[] authenticator) {
        this.requestAuthenticator = authenticator;
    }

    /**
     * Gets the username.
     *
     * @return The username.
     */
    protected byte[] username() {
        return this.username;
    }

    /**
     * Return the identifier of the state machine.
     *
     * @return The state machine identifier.
     */
    public synchronized byte identifier() {
        //identifier 0 is for statusServerrequest
        //identifier 1 is for fake accessRequest
        identifier = (identifier + 1) % 253;
        identifierMap.put((identifier + 2), this);
        return (byte) (identifier + 2);
    }

    /**
     * Move to the next state.
     *
     * @param msg message
     */
    private void next(int msg) {
        currentState = transition[currentState][msg];
        log.info("Current State " + currentState);
    }

    /**
     * Client has requested the start action to allow network access.
     *
     * @throws StateMachineException if authentication protocol is violated
     */
    public void start() throws StateMachineException {
        states[currentState].start();

        delegate.notify(new AuthenticationEvent(AuthenticationEvent.Type.STARTED, supplicantConnectpoint));

        // move to the next state
        next(TRANSITION_START);
        identifier = this.identifier();
    }

    /**
     * An Identification information has been sent by the supplicant. Move to the
     * next state if possible.
     *
     * @throws StateMachineException if authentication protocol is violated
     */
    public void requestAccess() throws StateMachineException {
        states[currentState].requestAccess();

        delegate.notify(new AuthenticationEvent(AuthenticationEvent.Type.REQUESTED, supplicantConnectpoint));

        // move to the next state
        next(TRANSITION_REQUEST_ACCESS);
    }

    /**
     * RADIUS has accepted the identification. Move to the next state if possible.
     *
     * @throws StateMachineException if authentication protocol is violated
     */
    public void authorizeAccess() throws StateMachineException {
        states[currentState].radiusAccepted();
        // move to the next state
        next(TRANSITION_AUTHORIZE_ACCESS);

        delegate.notify(new AuthenticationEvent(AuthenticationEvent.Type.APPROVED, supplicantConnectpoint));

        // Clear mapping
        deleteStateMachineMapping(this);
    }

    /**
     * RADIUS has denied the identification. Move to the next state if possible.
     *
     * @throws StateMachineException if authentication protocol is violated
     */
    public void denyAccess() throws StateMachineException {
        states[currentState].radiusDenied();
        // move to the next state
        next(TRANSITION_DENY_ACCESS);

        delegate.notify(new AuthenticationEvent(AuthenticationEvent.Type.DENIED, supplicantConnectpoint));

        // Clear mappings
        deleteStateMachineMapping(this);
    }

    /**
     * Logoff request has been requested. Move to the next state if possible.
     *
     * @throws StateMachineException if authentication protocol is violated
     */
    public void logoff() throws StateMachineException {
        states[currentState].logoff();
        // move to the next state
        next(TRANSITION_LOGOFF);
    }

    /**
     * Gets the current state.
     *
     * @return The current state. Could be STATE_IDLE, STATE_STARTED, STATE_PENDING,
     *         STATE_AUTHORIZED, STATE_UNAUTHORIZED.
     */
    public int state() {
        return currentState;
    }

    @Override
    public String toString() {
        return ("sessionId: " + this.sessionId) + "\t" + ("identifier: " + this.identifier) + "\t"
                + ("state: " + this.currentState);
    }

    abstract class State {
        private final Logger log = getLogger(getClass());

        private String name = "State";

        public void start() throws StateMachineInvalidTransitionException {
            log.warn("START transition from this state is not allowed.");
        }

        public void requestAccess() throws StateMachineInvalidTransitionException {
            log.warn("REQUEST ACCESS transition from this state is not allowed.");
        }

        public void radiusAccepted() throws StateMachineInvalidTransitionException {
            log.warn("AUTHORIZE ACCESS transition from this state is not allowed.");
        }

        public void radiusDenied() throws StateMachineInvalidTransitionException {
            log.warn("DENY ACCESS transition from this state is not allowed.");
        }

        public void logoff() throws StateMachineInvalidTransitionException {
            log.warn("LOGOFF transition from this state is not allowed.");
        }
    }

    /**
     * Idle state: supplicant is logged of from the network.
     */
    class Idle extends State {
        private final Logger log = getLogger(getClass());
        private String name = "IDLE_STATE";

        @Override
        public void start() {
            log.info("Moving from IDLE state to STARTED state.");
        }
    }

    /**
     * Started state: supplicant has entered the network and informed the
     * authenticator.
     */
    class Started extends State {
        private final Logger log = getLogger(getClass());
        private String name = "STARTED_STATE";

        @Override
        public void requestAccess() {
            log.info("Moving from STARTED state to PENDING state.");
        }
    }

    /**
     * Pending state: supplicant has been identified by the authenticator but has
     * not access yet.
     */
    class Pending extends State {
        private final Logger log = getLogger(getClass());
        private String name = "PENDING_STATE";

        @Override
        public void radiusAccepted() {
            log.info("Moving from PENDING state to AUTHORIZED state.");
        }

        @Override
        public void radiusDenied() {
            log.info("Moving from PENDING state to UNAUTHORIZED state.");
        }
    }

    /**
     * Authorized state: supplicant port has been accepted, access is granted.
     */
    class Authorized extends State {
        private final Logger log = getLogger(getClass());
        private String name = "AUTHORIZED_STATE";

        @Override
        public void start() {
            log.info("Moving from AUTHORIZED state to STARTED state.");
        }

        @Override
        public void logoff() {

            log.info("Moving from AUTHORIZED state to IDLE state.");
        }
    }

    /**
     * Unauthorized state: supplicant port has been rejected, access is denied.
     */
    class Unauthorized extends State {
        private final Logger log = getLogger(getClass());
        private String name = "UNAUTHORIZED_STATE";

        @Override
        public void start() {
            log.info("Moving from UNAUTHORIZED state to STARTED state.");
        }

        @Override
        public void logoff() {
            log.info("Moving from UNAUTHORIZED state to IDLE state.");
        }
    }

    /**
     * Class for cleaning the StateMachine for those session for which no response
     * is coming--implementing timeout.
     */
    class CleanupTimerTask implements Runnable {
        private final Logger log = getLogger(getClass());
        private String sessionId;
        private AaaManager aaaManager;

        CleanupTimerTask(String sessionId, AaaManager aaaManager) {
            this.sessionId = sessionId;
            this.aaaManager = aaaManager;
        }

        @Override
        public void run() {
            StateMachine stateMachine = StateMachine.lookupStateMachineBySessionId(sessionId);
            if (null != stateMachine) {
                // Asserting if last packet received for this stateMachine session was beyond half of timeout period.
                // StateMachine is considered eligible for cleanup when no packets has been exchanged by it with AAA
                // Server or RG during a long period (half of timeout period). For example, when cleanup timer has
                // been configured as 10 minutes, StateMachine would be cleaned up at the end of 10 minutes if
                // the authentication is still pending and no packet was exchanged for this session during last 5
                // minutes.

                boolean noTrafficWithinThreshold = (System.currentTimeMillis()
                        - stateMachine.getLastPacketReceivedTime()) > ((cleanupTimerTimeOutInMins * 60 * 1000) / 2);

                        if ((TIMEOUT_ELIGIBLE_STATES.contains(stateMachine.state())) && noTrafficWithinThreshold) {
                            log.info("Deleting StateMachineMapping for sessionId: {}", sessionId);
                            cleanupTimer = null;
                            if (stateMachine.state() == STATE_PENDING && stateMachine.isWaitingForRadiusResponse()) {
                                aaaManager.aaaStatisticsManager.getAaaStats().increaseTimedOutPackets();
                            }
                            deleteStateMachineId(sessionId);
                            deleteStateMachineMapping(stateMachine);

                            // If StateMachine is not eligible for cleanup yet, reschedule cleanupTimer further.
                        } else {
                            aaaManager.scheduleStateMachineCleanupTimer(sessionId, stateMachine);
                        }
            } else {
                // This statement should not be logged; cleanupTimer should be cancelled for stateMachine
                // instances which have been authenticated successfully.
                log.warn("state-machine not found for sessionId: {}", sessionId);
            }

        }
    }

}
