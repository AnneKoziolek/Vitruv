package tools.vitruv.framework.vsum.branch.merge;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.resource.ResourceSet;

import tools.vitruv.change.atomic.EChange;
import tools.vitruv.change.atomic.hid.HierarchicalId;
import tools.vitruv.change.atomic.hid.internal.HierarchicalIdResolver;
import tools.vitruv.change.atomic.resolve.AtomicEChangeResolverHelper;
import tools.vitruv.change.atomic.uuid.Uuid;
import tools.vitruv.change.atomic.uuid.UuidResolver;
import tools.vitruv.change.composite.description.PropagatedChange;
import tools.vitruv.change.composite.description.VitruviusChange;
import tools.vitruv.change.composite.propagation.ChangePropagationListener;

/**
 * A {@link ChangePropagationListener} that captures primary EChanges during
 * {@code propagateChange()} calls and converts them from UUID-based to
 * HierarchicalId-based representations for serialization in semantic change logs.
 *
 * <p>The conversion happens in {@code finishedChangePropagation()} (after changes are applied)
 * because newly created objects don't have UUID→EObject mappings until after application.
 *
 * <p>Register on a VirtualModel via {@code virtualModel.addChangePropagationListener(capture)}.
 * After each commit, call {@link #drainChanges()} to retrieve and clear the buffered changes.
 */
public class ChangeLogCapture implements ChangePropagationListener {

    private static final Logger LOGGER = LogManager.getLogger(ChangeLogCapture.class);

    private final UuidResolver uuidResolver;
    private final HierarchicalIdResolver hierarchicalIdResolver;
    private final List<EChange<HierarchicalId>> bufferedChanges = new ArrayList<>();

    // Temporarily hold the input change between started/finished callbacks
    private VitruviusChange<Uuid> pendingChange;

    public ChangeLogCapture(UuidResolver uuidResolver, HierarchicalIdResolver hierarchicalIdResolver) {
        this.uuidResolver = uuidResolver;
        this.hierarchicalIdResolver = hierarchicalIdResolver;
    }

    public static ChangeLogCapture create(UuidResolver uuidResolver, ResourceSet resourceSet) {
        return new ChangeLogCapture(uuidResolver, HierarchicalIdResolver.create(resourceSet));
    }

    @Override
    public void startedChangePropagation(VitruviusChange<Uuid> changeToPropagate) {
        // Save reference; conversion happens after changes are applied (in finishedChangePropagation)
        pendingChange = changeToPropagate;
    }

    @Override
    public void finishedChangePropagation(Iterable<PropagatedChange> propagatedChanges) {
        if (pendingChange == null) {
            return;
        }
        try {
            // Now that changes have been applied, all UUIDs are resolvable to EObjects
            List<EChange<Uuid>> uuidChanges = pendingChange.getEChanges();
            for (EChange<Uuid> uuidChange : uuidChanges) {
                EChange<HierarchicalId> hidChange = convertToHierarchicalId(uuidChange);
                bufferedChanges.add(hidChange);
            }
            LOGGER.debug("Captured {} primary changes for changelog", uuidChanges.size());
        } catch (Exception e) {
            LOGGER.error("Failed to capture changes for changelog: {}", e.getMessage(), e);
        } finally {
            pendingChange = null;
        }
    }

    public List<EChange<HierarchicalId>> drainChanges() {
        List<EChange<HierarchicalId>> result = new ArrayList<>(bufferedChanges);
        bufferedChanges.clear();
        return Collections.unmodifiableList(result);
    }

    public int getBufferedChangeCount() {
        return bufferedChanges.size();
    }

    /**
     * Converts an EChange from Uuid-based to HierarchicalId-based representation.
     * Called after changes are applied, so all UUIDs map to existing EObjects.
     */
    private EChange<HierarchicalId> convertToHierarchicalId(EChange<Uuid> uuidChange) {
        return AtomicEChangeResolverHelper.resolveChange(
                uuidChange,
                uuid -> {
                    EObject eObject = uuidResolver.getEObject(uuid);
                    return hierarchicalIdResolver.getAndUpdateId(eObject);
                },
                resource -> resource
        );
    }
}
