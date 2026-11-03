import { useCallback, useEffect, useRef, useState } from "react";

import { useDashboardContext } from "metabase/dashboard/context";
import resizeObserver from "metabase/utils/resize-observer";

type Opts = {
  reservedWidth: number;
  bufferSpace?: number;
};

export function useResponsiveParameterList({
  reservedWidth,
  bufferSpace = 24,
}: Opts) {
  const { isEditing, isEditingParameter } = useDashboardContext();

  const [shouldCollapseList, setShouldCollapseList] = useState(false);

  const containerRef = useRef<HTMLDivElement>(null);
  // Kept in state so the check reruns when the list mounts. Scalar cards
  // render their filters only after the data has loaded, without resizing.
  const [parameterList, parameterListRef] = useState<HTMLDivElement | null>(
    null,
  );

  const checkForCollision = useCallback(() => {
    if (!containerRef.current || !parameterList) {
      return false;
    }

    const { width: containerWidth } =
      containerRef.current.getBoundingClientRect();
    const { width: parametersWidth } = parameterList.getBoundingClientRect();

    const requiredWidth = reservedWidth + parametersWidth + bufferSpace;

    return requiredWidth > containerWidth;
  }, [reservedWidth, bufferSpace, parameterList]);

  useEffect(() => {
    if (isEditingParameter) {
      return;
    }

    const updateCollisionState = () => {
      const shouldCollapse = checkForCollision();
      setShouldCollapseList(shouldCollapse);
    };

    updateCollisionState();

    const element = containerRef.current;
    if (!element) {
      return;
    }

    resizeObserver.subscribe(element, updateCollisionState);
    // Filter values change the width of the list, not of the card.
    if (parameterList) {
      resizeObserver.subscribe(parameterList, updateCollisionState);
    }
    return () => {
      resizeObserver.unsubscribe(element, updateCollisionState);
      if (parameterList) {
        resizeObserver.unsubscribe(parameterList, updateCollisionState);
      }
    };
  }, [checkForCollision, isEditing, isEditingParameter, parameterList]);

  return {
    shouldCollapseList,
    containerRef,
    parameterListRef,
  };
}
