"""Observe selected frozen births only inside their sole-owned EXEC stop.

No CLI, probe launch, ABI exception or terminal authority. The
trusted host must freeze the birth/record selection before wait dispatch starts.
"""
from fixed_witness_replay_abi_model import AbiModelError, AbiProbeModel, ExecIdentity, ProbeRecord
from fixed_witness_replay_abi_observation import AbiExecObserver, AbiObservationError


class OwnedExecObservations:
    """At most two fixed probe births; one observation attempt per birth.

    Optional model binding happens inside the same held EXEC gate. It grants no
    syscall exception: the ordinary native dispatcher
    still owns resume and all ABI rejection. Failed observations latch both this
    configuration and the outer wait session, retaining migrated cleanup state.
    """

    def __init__(self, records, *, files=None, bind_abi=False):
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
        # Construct our own pristine two-slot model from frozen expectations;
        # never accept a caller's mutable or already-consumed model.
        self._abi_model = AbiProbeModel(tuple(record for _, record in records)) if bind_abi else None
        self._files = files
        self._dispatcher = None
        self._attempted = set()
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

    def observe(self, dispatcher, matched, generation):
        """Internal dispatch hook, while the exact charged wait is still held."""
        try:
            if self.stopped is not None or dispatcher is not self._dispatcher:
                raise AbiObservationError('exec observation session stopped or foreign')
            if self._abi_model is not None and self._abi_model.stopped is not None:
                raise AbiObservationError('ABI binding model stopped')
            if matched.birth not in self._records:
                return
            if matched.birth in self._attempted:
                raise AbiObservationError('selected probe birth executed again')
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
            observer = AbiExecObserver(launch.owner, check, files=self._files)
            observation = observer.observe(self._records[expected.birth], expected)
            check()
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
