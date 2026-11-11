"""Bind selected frozen births and consume their sole-owned fixed ABI stops.

No CLI, probe launch or whole-capability authority. The
trusted host must freeze the birth/record selection before wait dispatch starts.
"""
from dataclasses import dataclass

from fixed_witness_replay_abi_model import AbiModelError, AbiProbeModel, ExecIdentity, ProbeRecord
from fixed_witness_replay_abi_observation import AbiExecObserver, AbiObservationError
from fixed_witness_replay_ptrace import SyscallInfo
from fixed_witness_replay_wait import decode_wait_status
from fixed_witness_replay_probe_launch import ProbeLaunch


@dataclass(frozen=True)
class _AbiResume:
    """Internal original-stop attempt, not a filter/no-injection certificate."""

    identity: ExecIdentity
    manifest: str
    receipt: object
    facts: object
    signal: int


class OwnedExecObservations:
    """At most two fixed probe births; one observation attempt per birth.

    Optional model binding happens inside the same held EXEC gate. Only its two
    exact one-shot entries bypass the ordinary native policy, under the original
    wait owner. PostHandshakeDispatcher is the single resume entry point and
    delegates fixed ABI resumes to this configuration. Failed observations
    latch this configuration and the outer wait session, retaining cleanup state.
    """

    def __init__(self, records, *, files=None, bind_abi=False, network_launches=()):
        if type(bind_abi) is not bool:
            raise AbiObservationError('explicit boolean ABI binding selection required')
        if (type(records) is not tuple or not 1 <= len(records) <= 2
                or any(type(item) is not tuple or len(item) != 2 for item in records)):
            raise AbiObservationError('fixed birth/record pairs required')
        if (any(type(birth) is not int or not 0 < birth < 2**64
                or type(record) is not ProbeRecord or not record.valid()
                for birth, record in records)
                or len({birth for birth, _ in records}) != len(records)
                or len({record.mode for _, record in records}) != len(records)):
            raise AbiObservationError('unique fixed probe births and modes required')
        self._records = dict(records)
        if (type(network_launches) is not tuple
                or any(type(pair) is not tuple or len(pair) != 2
                       or type(pair[0]) is not int or pair[0] not in self._records
                       or type(pair[1]) is not ProbeLaunch
                       or pair[1].probe != self._records[pair[0]] for pair in network_launches)
                or len({pair[0] for pair in network_launches}) != len(network_launches)
                or (network_launches and {pair[0] for pair in network_launches} != set(self._records))):
            raise AbiObservationError('exact frozen network launch per selected birth required')
        self._network_launches = dict(network_launches)
        self._launch_observations = {}
        self._launch_resume_attempts = set()
        self._launch_resumed = set()
        # Construct our own pristine two-slot model from frozen expectations;
        # never accept a caller's mutable or already-consumed model.
        self._abi_model = AbiProbeModel(tuple(record for _, record in records)) if bind_abi else None
        self._files = files
        self._dispatcher = None
        self._attempted = set()
        self._entry_resumes = {}
        self._completed_entries = set()
        # Keep constant space per frozen birth, even with many delivery stops.
        self._last_signal_resumes = {}
        self.observations = ()
        self.stopped = None

    def abi_state(self, mode):
        """Diagnostic state only, not a transferable binding/admission receipt."""
        if self._abi_model is None:
            raise AbiObservationError('ABI model binding not selected')
        return self._abi_model.state(mode)

    def claim(self, dispatcher):
        if self._dispatcher is not None:
            raise AbiObservationError('exec observation configuration already claimed')
        self._dispatcher = dispatcher

    def fail(self, reason):
        """Revoke on outer dispatch/resume failure without restoring a slot."""
        if self.stopped is None:
            self.stopped = reason
        if self._abi_model is not None:
            try:
                self._abi_model.fail(self.stopped)
            except AbiModelError:
                pass

    def observe_stop(self, dispatcher, birth, facts):
        """Consume owned fixed ENTRY/delivery facts; never read or resume.

        True only selects the exact ABI entry instead of native syscall policy.
        This internal selection is not a transferable admission receipt or a
        filter/source/no-injection capability certificate. The host integration
        and whole-fixture gates remain required before any native execution.
        """
        if self._abi_model is None:
            return False
        try:
            if (self.stopped is not None or dispatcher is not self._dispatcher
                    or self._abi_model.stopped is not None):
                raise AbiObservationError('ABI stop session stopped or foreign')
            if birth not in self._records:
                return False
            record = self._records[birth]
            state = self._abi_model.state(record.mode)
            if state == 'UNBOUND':
                return False
            if state not in ('BOUND', 'AWAITING_SIGSYS'):
                raise AbiObservationError('ABI slot no longer usable')
            observed = tuple(item for item in self.observations if item.identity.birth == birth)
            if len(observed) != 1 or observed[0].record != record:
                raise AbiObservationError('completed owned EXEC observation required')
            expected = observed[0].identity
            launch = dispatcher.launch
            receipt = dispatcher._active_wait
            owner = getattr(dispatcher, '_wait_owner', None)
            wire = launch.dispatcher.stop_binding(expected.tid)[1]

            def check():
                if owner is None:
                    raise AbiObservationError('original active wait owner required')
                owner._reap_gates()
                if (owner.dispatcher is not dispatcher or owner.last_wait is not receipt
                        or receipt is None or dispatcher._active_wait is not receipt
                        or receipt.adopted or receipt.facts is not facts
                        or facts.tid != expected.tid or receipt.tid != expected.tid
                        or facts.status != decode_wait_status(receipt.raw)
                        or receipt.parent != launch.ledger.identity(birth)
                        or not receipt.parent.alive or receipt.parent.birth != birth
                        or receipt.parent.tid != receipt.parent.tgid
                        or owner._exec_identity(receipt.parent) != expected
                        or launch.dispatcher.stop_binding(expected.tid) != (birth, wire)
                        or self.stopped is not None or self._abi_model.stopped is not None):
                    raise AbiObservationError('exclusive charged ABI stop lost')

            check()
            kind = facts.status.kind
            if state == 'AWAITING_SIGSYS' and kind != 'signal-stop':
                raise AbiObservationError('ABI entry already consumed; only signal or terminal allowed')
            handled = False
            if kind == 'syscall-stop':
                info = facts.syscall
                if type(info) is not SyscallInfo:
                    raise AbiObservationError('decoded syscall-info required')
                # Native facts retain the complete existing sequence/creation
                # path. Non-native facts must match the model without masking.
                if info.arch != 0xc000003e or (info.operation == 1 and info.number >= 0x40000000):
                    creation = launch.dispatcher
                    if (creation._sequence.get(birth) is not None
                            or birth in creation._pending or birth in creation._exec_events
                            or birth in creation._exec_exits or birth in dispatcher._newborn):
                        raise AbiObservationError('ABI entry requires settled native sequence')
                    self._abi_model.consume_entry(record.mode, expected,
                                                  self._abi_model.manifest_sha256, info)
                    handled = True
            elif kind == 'signal-stop' and facts.status.signal == 31:
                self._abi_model.observe_sigsys(record.mode, expected,
                                               self._abi_model.manifest_sha256, facts.signal_info)
            check()
            return handled
        except BaseException:
            self.fail('owned ABI stop failed; no retry')
            dispatcher.stopped = dispatcher.launch.stopped = self.stopped
            raise

    def observe_reap(self, dispatcher, record):
        """Join an original settled EXEC reap to this configuration's model.

        This hook runs only after normal terminal settlement. It adds no wait,
        resume, credit release or ABI entry permission. A matched terminal is
        still not filter/no-injection proof or KERNEL_TERMINAL_OBSERVED.
        """
        if self._abi_model is None:
            return
        try:
            if self.stopped is not None or dispatcher is not self._dispatcher:
                raise AbiObservationError('terminal observation session stopped or foreign')
            owner = getattr(dispatcher, '_wait_owner', None)
            if owner is None:
                raise AbiObservationError('original settled wait owner required')
            # Validate provenance even before inspecting the birth. Neither a
            # copied record nor an external status tuple may select a slot.
            owner.require_settled_reap(record)
            if record.before.birth not in self._records:
                return
            observed = tuple(item for item in self.observations
                             if item.identity.birth == record.before.birth)
            if len(observed) != 1 or observed[0].record != self._records[record.before.birth]:
                raise AbiObservationError('completed owned EXEC observation required')
            observation = observed[0]
            owner.require_exec_reap(record, observation.identity)
            self._require_entry_resume(observation.identity)
            self._abi_model.observe_terminal(
                observation.record.mode, observation.identity,
                self._abi_model.manifest_sha256, record.status)
            # A fallible post-match gate never restores a consumed model slot
            # or rolls back the real reap. Failure revokes the whole session.
            owner.require_exec_reap(record, observation.identity)
        except BaseException:
            self.stopped = 'owned terminal observation failed; no retry'
            dispatcher.stopped = dispatcher.launch.stopped = self.stopped
            try:
                self._abi_model.fail(self.stopped)
            except AbiModelError:
                pass
            raise

    def _require_entry_resume(self, identity):
        attempt = self._entry_resumes.get(identity.birth)
        if (attempt is None or identity.birth not in self._completed_entries
                or attempt.identity != identity or attempt.manifest != self._abi_model.manifest_sha256
                or attempt.signal != 0 or attempt.receipt.facts is not attempt.facts
                or attempt.receipt.adopted or attempt.receipt.tid != identity.tid
                or attempt.facts.tid != identity.tid
                or attempt.facts.status != decode_wait_status(attempt.receipt.raw)
                or attempt.facts.status.kind != 'syscall-stop'):
            raise AbiObservationError('original completed ABI ENTRY resume required')

    def resume(self, dispatcher, birth, facts, wire, signal):
        """Own the exact fixed ENTRY/delivery resume, recording uncertain attempts.

        No injected signal, register write, second read or retry is performed.
        Completion means only that this original wire call returned and shared
        gates held. Source/filter installation and external no-injection proof
        remain host/checker prerequisites; these records do not certify them.
        """
        if (birth in self._launch_observations
                and facts.status.kind == 'ptrace-event' and facts.status.event == 'EXEC'
                and not any(item.identity.birth == birth for item in self.observations)):
            try:
                identity = self._launch_observations[birth].identity
                owner = getattr(dispatcher, '_wait_owner', None)
                receipt = dispatcher._active_wait
                def check():
                    if owner is None:
                        raise AbiObservationError('original launcher wait owner required')
                    owner._reap_gates()
                    if (dispatcher is not self._dispatcher or self.stopped is not None
                            or owner.last_wait is not receipt or receipt is None
                            or receipt.adopted or receipt.facts is not facts
                            or receipt.raw != 0x4057f or receipt.tid != identity.tid
                            or not receipt.event_attempted or not receipt.event_read
                            or owner._exec_identity(dispatcher.launch.ledger.identity(birth)) != identity
                            or dispatcher.launch.dispatcher.stop_binding(identity.tid) != (birth, wire)
                            or type(signal) is not int or signal != 0):
                        raise AbiObservationError('original launcher EXEC resume lost')
                check()
                if birth in self._launch_resume_attempts:
                    raise AbiObservationError('launcher resume already attempted')
                self._launch_resume_attempts.add(birth)
                wire.resume_syscall(identity.tid, 0)
                self._launch_resumed.add(birth)
                check()
                return True
            except BaseException:
                self.fail('owned launcher resume failed; no retry')
                dispatcher.stopped = dispatcher.launch.stopped = self.stopped
                raise
        if (self._abi_model is None or birth not in self._records
                or self._abi_model.state(self._records[birth].mode) != 'AWAITING_SIGSYS'):
            return False
        try:
            owner = getattr(dispatcher, '_wait_owner', None)
            receipt = dispatcher._active_wait
            record = self._records[birth]
            observation, = (item for item in self.observations if item.identity.birth == birth)
            identity = observation.identity

            def check():
                if owner is None:
                    raise AbiObservationError('original resume wait owner required')
                owner._reap_gates()
                if (dispatcher is not self._dispatcher or self.stopped is not None
                        or self._abi_model.stopped is not None
                        or owner.last_wait is not receipt or dispatcher._active_wait is not receipt
                        or receipt is None or receipt.adopted or receipt.facts is not facts
                        or receipt.tid != identity.tid or facts.tid != identity.tid
                        or facts.status != decode_wait_status(receipt.raw)
                        or receipt.parent != dispatcher.launch.ledger.identity(birth)
                        or not receipt.parent.alive or owner._exec_identity(receipt.parent) != identity
                        or dispatcher.launch.dispatcher.stop_binding(identity.tid) != (birth, wire)
                        or observation.record != record):
                    raise AbiObservationError('exclusive ABI resume stop lost')

            check()
            if facts.status.kind == 'syscall-stop':
                if type(signal) is not int or signal != 0 or birth in self._entry_resumes:
                    raise AbiObservationError('one original signal-zero ENTRY resume required')
                # Revalidate the exact already-consumed entry without consuming
                # the slot again. The original immutable facts must still match.
                info = facts.syscall
                expected = (0xc000003e, 0x40000029) if record.mode == 'x32' else (0x40000003, 102)
                if type(info) is not SyscallInfo or info.operation != 1 or (info.arch, info.number) != expected:
                    raise AbiObservationError('original fixed ENTRY facts required')
                attempt = _AbiResume(identity, self._abi_model.manifest_sha256, receipt, facts, signal)
                self._entry_resumes[birth] = attempt
            elif facts.status.kind == 'signal-stop':
                self._require_entry_resume(identity)
                if type(signal) is not int or signal != facts.status.signal:
                    raise AbiObservationError('original delivery signal must be preserved')
                if signal == 31:
                    self._abi_model.observe_sigsys(record.mode, identity,
                                                   self._abi_model.manifest_sha256, facts.signal_info)
                self._last_signal_resumes[birth] = _AbiResume(
                    identity, self._abi_model.manifest_sha256, receipt, facts, signal)
            else:
                raise AbiObservationError('unsupported ABI resume stop')
            check()
            wire.resume_syscall(identity.tid, signal)
            if facts.status.kind == 'syscall-stop':
                self._completed_entries.add(birth)
            check()
            return True
        except BaseException:
            self.fail('owned ABI resume failed; no retry')
            dispatcher.stopped = dispatcher.launch.stopped = self.stopped
            raise

    def observe(self, dispatcher, matched, generation):
        """Internal dispatch hook, while the exact charged wait is still held."""
        try:
            if self.stopped is not None or dispatcher is not self._dispatcher:
                raise AbiObservationError('exec observation session stopped or foreign')
            if self._abi_model is not None and self._abi_model.stopped is not None:
                raise AbiObservationError('ABI binding model stopped')
            if matched.birth not in self._records:
                return
            network_launch = self._network_launches.get(matched.birth)
            launcher = network_launch is not None and matched.birth not in self._launch_observations
            if matched.birth in self._attempted:
                raise AbiObservationError('selected probe birth executed again')
            if not launcher:
                self._attempted.add(matched.birth)
            launch = dispatcher.launch
            receipt = dispatcher._active_wait
            identity = launch.ledger.identity(matched.birth)
            expected = ExecIdentity(identity.birth, identity.group_birth,
                                    identity.tid, generation)
            if not expected.valid():
                raise AbiObservationError('invalid installed exec identity')
            wire = launch.dispatcher.stop_binding(matched.new_tid)[1]

            def check():
                owner = getattr(dispatcher, '_wait_owner', None)
                if (dispatcher.stopped is not None or launch.stopped is not None
                        or (self._abi_model is not None and self._abi_model.stopped is not None)
                        or launch.reader.stopped is not None
                        or launch.dispatcher.stopped is not None
                        or launch.dispatcher.lifecycle.stopped is not None
                        or launch.dispatcher.lifecycle.policy.stopped is not None
                        or launch.ledger.totals.stopped is not None
                        or owner is None or owner.stopped is not None
                        or owner.dispatcher is not dispatcher
                        or owner.last_wait is not receipt or receipt is None
                        or dispatcher._active_wait is not receipt
                        or receipt.raw != 0x4057f or receipt.tid != matched.new_tid
                        or not receipt.event_attempted or not receipt.event_read
                        or receipt.event_tid != matched.old_tid or receipt.adopted
                        or dispatcher._exec_generations.get(expected.birth) != generation):
                    raise AbiObservationError('exclusive charged EXEC stop lost')
                launch._owner_and_deadline()
                current = launch.ledger.identity(expected.birth)
                if (current != identity or not current.alive
                        or current.tid != current.tgid
                        or current.tid != matched.new_tid
                        or launch.dispatcher.stop_binding(current.tid) != (expected.birth, wire)
                        or expected.birth not in launch.dispatcher._exec_exits):
                    raise AbiObservationError('installed EXEC birth changed')
                return expected

            check()
            if network_launch is not None and not launcher:
                previous = self._launch_observations[expected.birth]
                if (expected.birth not in self._launch_resumed
                        or expected != ExecIdentity(previous.identity.birth,
                                                    previous.identity.group_birth,
                                                    previous.identity.tid,
                                                    previous.identity.generation + 1)):
                    raise AbiObservationError('original adjacent launcher EXEC required')
            observer = AbiExecObserver(launch.owner, check, files=self._files)
            if network_launch is None:
                observation = observer.observe(self._records[expected.birth], expected)
            else:
                observation = observer.observe(self._records[expected.birth], expected,
                                               network_launch=network_launch, launcher=launcher)
            check()
            if launcher:
                self._launch_observations[expected.birth] = observation
                return
            if network_launch is not None:
                previous = self._launch_observations[expected.birth]
                if previous.start_ticks != observation.start_ticks:
                    raise AbiObservationError('launcher/probe process lifetime changed')
            if self._abi_model is not None:
                self._abi_model.bind(observation.record.mode, observation.identity, observation.record)
                # Retain a completed binding if this last gate fails, but latch
                # the model/session; no rollback or reuse of a consumed slot.
                check()
            self.observations += (observation,)
        except BaseException:
            self.stopped = 'owned exec observation failed; no retry'
            dispatcher.stopped = dispatcher.launch.stopped = self.stopped
            if self._abi_model is not None:
                try:
                    self._abi_model.fail(self.stopped)
                except AbiModelError:
                    pass
            raise
