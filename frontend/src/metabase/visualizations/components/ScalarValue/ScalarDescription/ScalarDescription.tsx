import { DescriptionTooltip } from "metabase/common/components/DescriptionTooltip";
import { useTranslateContent } from "metabase/content-translation/hooks";
import { Box, Icon } from "metabase/ui";

import S from "./ScalarDescription.module.css";

export const DESCRIPTION_ICON_SIZE = 16;

export function ScalarDescription({ description }: { description: string }) {
  const tc = useTranslateContent();

  return (
    <DescriptionTooltip description={tc(description)}>
      <Box component="span" tabIndex={0} className={S.descriptionIcon}>
        <Icon name="info" size={DESCRIPTION_ICON_SIZE} />
      </Box>
    </DescriptionTooltip>
  );
}
