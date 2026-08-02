import { Slot } from "@radix-ui/react-slot";
import { cva, type VariantProps } from "class-variance-authority";
import type { ButtonHTMLAttributes } from "react";
import { cn } from "./class-names";

const buttonVariants = cva("button", {
  variants: {
    variant: {
      default: "button--primary",
      outline: "button--outline",
      ghost: "button--ghost"
    },
    size: {
      default: "button--default",
      icon: "button--icon"
    }
  },
  defaultVariants: {
    variant: "default",
    size: "default"
  }
});

type ButtonProps = ButtonHTMLAttributes<HTMLButtonElement> &
  VariantProps<typeof buttonVariants> & {
    asChild?: boolean;
  };

export function Button({ asChild, className, variant, size, ...props }: ButtonProps) {
  const Component = asChild ? Slot : "button";
  return <Component className={cn(buttonVariants({ variant, size }), className)} {...props} />;
}
