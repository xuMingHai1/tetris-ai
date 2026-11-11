"""Observe selected frozen births only inside their sole-owned EXEC stop.

No CLI, probe launch, ABI exception, model binding or terminal authority. The
trusted host must freeze the birth/record selection before wait dispatch starts.
"""
from fixed_witness_replay_abi_model import ExecIdentity, ProbeRecord
from fixed_witness_replay_abi_observation import AbiExecObserver, AbiObservationError


class OwnedExecObservations:
    """At most two fixed probe births; one observation attempt per birth.

    Successful return records observations only. The ordinary native dispatcher
    still owns resume and all ABI rejection. Failed observations latch both this
    configuration and the outer wait session, retaining migrated cleanup state.
    """

    def __init__(self, records, *, files=None):
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
        self._files = files
        self._dispatcher = None
        self._attempted = set()
        self.observations = ()
        self.stopped = None

    def claim(self, dispatcher):
        if self._dispatcher is not None:
            raise AbiObservationError('exec observation configuration already claimed')
        self._dispatcher = dispatcher

    def observe(self, dispatcher, matched, generation):
        """Internal dispatch hook, while the exact charged wait is still held."""
        try:
            if self.stopped is not None or dispatcher is not self._dispatcher:
                raise AbiObservationError('exec observation session stopped or foreign')
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
            self.observations += (observation,)
        except BaseException:
            self.stopped = 'owned exec observation failed; no retry'
            dispatcher.stopped = dispatcher.launch.stopped = self.stopped
            raise
