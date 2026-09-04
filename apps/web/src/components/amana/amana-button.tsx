"use client";

import { Button } from "@razorpay/blade/components";
import type { ComponentProps } from "react";

type BladeButtonProps = ComponentProps<typeof Button>;

type AmanaButtonProps = {
  accessibilityLabel?: BladeButtonProps["accessibilityLabel"];
  children: string;
  href?: BladeButtonProps["href"];
  isDisabled?: BladeButtonProps["isDisabled"];
  isFullWidth?: BladeButtonProps["isFullWidth"];
  isLoading?: BladeButtonProps["isLoading"];
  onClick?: BladeButtonProps["onClick"];
  type?: BladeButtonProps["type"];
  variant?: BladeButtonProps["variant"];
};

/**
 * A narrow shared contract keeps application actions on Blade without exposing
 * a parallel styling API. Blade's large size supplies a 48px touch target.
 */
export function AmanaButton({ variant = "primary", ...props }: AmanaButtonProps) {
  return <Button {...props} icon={undefined} size="large" variant={variant} />;
}
