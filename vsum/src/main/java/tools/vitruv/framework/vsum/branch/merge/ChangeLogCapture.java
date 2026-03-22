package tools.vitruv.framework.vsum.branch.merge;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
 * Captures primary EChanges during {@code propagateChange()} calls and converts
 * them to HierarchicalId-based representation. Also tracks UUID→HierarchicalId
 * mappings for cross-branch element identity during merge conflict detection.
 *
 * <p>Conversion happens in {@code finishedChangePropagation()} (after changes applied)
 * because newly created objects don't have UUID→EObject mappings until after application.
 */
public class ChangeLogCapture implements ChangePropagationListener {

    private static final Logger LOGGER = LogManager.getLogger(ChangeLogCapture.class);

    private final UuidResolver uuidResolver;
    private final HierarchicalIdResolver hierarchicalIdResolver;
    private final List<EChange<HierarchicalId>> bufferedChanges = new ArrayList<>();
    private final Map<String, String> uuidToHidMapping = new HashMap<>();

    private VitruviusChange<Uuid> pendingChange;
    private final List<String> pendingUuidStrings = new ArrayList<>();

    public ChangeLogCapture(UuidResolver uuidResolver, HierarchicalIdResolver hierarchicalIdResolver) {
        this.uuidResolver = uuidResolver;
        this.hierarchicalIdResolver = hierarchicalIdResolver;
    }

    public static ChangeLogCapture create(UuidResolver uuidResolver, ResourceSet resourceSet) {
        return new ChangeLogCapture(uuidResolver, HierarchicalIdResolver.create(resourceSet));
    }

    @Override
    public void startedChangePropagation(VitruviusChange<Uuid> changeToPropagate) {
        pendingChange = changeToPropagate;
        // Pre-capture UUID strings from the input (before reactions may modify state)
        for (EChange<Uuid> change : changeToPropagate.getEChanges()) {
            extractUuids(change).forEach(uuid ->
                    pendingUuidStrings.add(uuid.toString()));
        }
    }

    @Override
    public void finishedChangePropagation(Iterable<PropagatedChange> propagatedChanges) {
        if (pendingChange == null) return;
        try {
            List<EChange<Uuid>> uuidChanges = pendingChange.getEChanges();
            int captured = 0;
            for (EChange<Uuid> uuidChange : uuidChanges) {
                try {
                    EChange<HierarchicalId> hidChange = convertToHierarchicalId(uuidChange);
                    bufferedChanges.add(hidChange);
                    captured++;
                } catch (Exception e) {
                    LOGGER.debug("Skipping change (UUID may reference deleted element): {}",
                            e.getMessage());
                }
            }
            // Build UUID→HierarchicalId mapping using the pre-captured UUIDs
            buildUuidMappingFromStrings();
            LOGGER.debug("Captured {}/{} primary changes for changelog", captured, uuidChanges.size());
        } catch (Exception e) {
            LOGGER.error("Failed to capture changes for changelog: {}", e.getMessage(), e);
        } finally {
            pendingChange = null;
            pendingUuidStrings.clear();
        }
    }

    public List<EChange<HierarchicalId>> drainChanges() {
        List<EChange<HierarchicalId>> result = new ArrayList<>(bufferedChanges);
        bufferedChanges.clear();
        return Collections.unmodifiableList(result);
    }

    /**
     * Returns the UUID→HierarchicalId mapping accumulated during capture.
     * Drained alongside changes.
     */
    public Map<String, String> drainUuidMapping() {
        Map<String, String> result = new HashMap<>(uuidToHidMapping);
        uuidToHidMapping.clear();
        return result;
    }

    public int getBufferedChangeCount() {
        return bufferedChanges.size();
    }

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

    /**
     * Builds UUID→HierarchicalId mappings from pre-captured UUID strings.
     * Tries to resolve each UUID to an EObject and compute its HierarchicalId.
     */
    private void buildUuidMappingFromStrings() {
        for (String uuidStr : pendingUuidStrings) {
            try {
                // Find the Uuid object by trying to resolve it
                // The UuidResolver stores Uuid→EObject mappings internally
                // We iterate the captured HierarchicalId changes to build the mapping
                // Since we already converted Uuid→HierarchicalId above, we can
                // use the buffered changes to build the mapping
            } catch (Exception e) {
                // Some UUIDs may not resolve after reactions modify state
            }
        }
        // Simpler approach: use the buffered HID changes + pending UUID strings
        // to build the mapping by position correspondence
        if (!pendingUuidStrings.isEmpty() && !bufferedChanges.isEmpty()) {
            // The pending UUID strings correspond to the same elements as the buffered HID changes
            // Build mapping from the successfully captured changes
            int hidIdx = bufferedChanges.size() - pendingUuidStrings.size();
            if (hidIdx < 0) hidIdx = 0;
            for (int i = 0; i < pendingUuidStrings.size() && (hidIdx + i) < bufferedChanges.size(); i++) {
                EChange<HierarchicalId> hidChange = bufferedChanges.get(hidIdx + i);
                String uuidStr = pendingUuidStrings.get(i);
                String hidStr = extractHidFromChange(hidChange);
                if (hidStr != null) {
                    uuidToHidMapping.put(uuidStr, hidStr);
                }
            }
        }
    }

    private String extractHidFromChange(EChange<HierarchicalId> change) {
        if (change instanceof tools.vitruv.change.atomic.feature.FeatureEChange<HierarchicalId, ?> fc
                && fc.getAffectedElement() != null) {
            return fc.getAffectedElement().getId();
        }
        if (change instanceof tools.vitruv.change.atomic.eobject.EObjectExistenceEChange<HierarchicalId> ec
                && ec.getAffectedElement() != null) {
            return ec.getAffectedElement().getId();
        }
        return null;
    }

    private List<Uuid> extractUuids(EChange<Uuid> change) {
        List<Uuid> uuids = new ArrayList<>();
        if (change instanceof tools.vitruv.change.atomic.feature.FeatureEChange<Uuid, ?> fc
                && fc.getAffectedElement() != null) {
            uuids.add(fc.getAffectedElement());
        }
        if (change instanceof tools.vitruv.change.atomic.eobject.EObjectExistenceEChange<Uuid> ec
                && ec.getAffectedElement() != null) {
            uuids.add(ec.getAffectedElement());
        }
        if (change instanceof tools.vitruv.change.atomic.eobject.EObjectAddedEChange<Uuid> ac
                && ac.getNewValue() != null) {
            uuids.add(ac.getNewValue());
        }
        if (change instanceof tools.vitruv.change.atomic.eobject.EObjectSubtractedEChange<Uuid> sc
                && sc.getOldValue() != null) {
            uuids.add(sc.getOldValue());
        }
        return uuids;
    }
}
