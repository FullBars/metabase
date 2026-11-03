import { t } from "ttag";

import { DescriptionTooltip } from "metabase/common/components/DescriptionTooltip";
import { EntityIcon } from "metabase/common/components/EntityIcon";
import type { IconData } from "metabase/common/utils/icon";
import { useGetIcon } from "metabase/hooks/use-icon";
import { Icon } from "metabase/ui";
import { isEmpty } from "metabase/utils/validate";
import type { UnrestrictedLinkEntity } from "metabase-types/api";

import {
  EllipsifiedEntityContainer,
  EntityDisplayContainer,
  LeftContainer,
} from "./EntityDisplay.styled";

export const EntityDisplay = ({
  entity,
  showDescription = false,
}: {
  entity: UnrestrictedLinkEntity;
  showDescription?: boolean;
}) => {
  const getIcon = useGetIcon();

  const getSearchIcon = (entity: UnrestrictedLinkEntity): IconData => {
    const entityIcon = getIcon(entity) ?? { name: "link" as const };
    if (entity.model === "table") {
      entityIcon.name = "database";
    }
    return entityIcon;
  };

  return (
    <EntityDisplayContainer>
      <LeftContainer>
        <EntityIcon color="core-brand" {...getSearchIcon(entity)} />
        <EllipsifiedEntityContainer>{entity?.name}</EllipsifiedEntityContainer>
      </LeftContainer>
      {showDescription && entity?.description && (
        <DescriptionTooltip
          description={entity.description}
          data-testid="icon-tooltip"
        >
          <Icon name="info" c="text-disabled" />
        </DescriptionTooltip>
      )}
    </EntityDisplayContainer>
  );
};

export const RestrictedEntityDisplay = () => (
  <EntityDisplayContainer>
    <LeftContainer>
      <Icon name="key" c="text-disabled" />
      <EllipsifiedEntityContainer>{t`Sorry, you don't have permission to see this link.`}</EllipsifiedEntityContainer>
    </LeftContainer>
  </EntityDisplayContainer>
);

export const UrlLinkDisplay = ({ url }: { url?: string }) => {
  // show a question mark icon for the empty state
  const urlIcon = isEmpty(url) ? "question" : "link";

  return (
    <EntityDisplayContainer>
      <LeftContainer>
        <Icon c={"core-brand"} name={urlIcon} />
        <EllipsifiedEntityContainer>
          {!isEmpty(url) ? url : t`Choose a link`}
        </EllipsifiedEntityContainer>
      </LeftContainer>
    </EntityDisplayContainer>
  );
};
