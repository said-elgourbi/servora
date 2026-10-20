import tseslint from 'typescript-eslint';
import angular from 'angular-eslint';
export default tseslint.config(
  { ignores: ['dist/**', '.angular/**', 'node_modules/**'] },
  {
    files: ['src/**/*.ts'],
    extends: [...tseslint.configs.recommended, ...angular.configs.tsRecommended],
    processor: angular.processInlineTemplates,
    rules: { '@angular-eslint/prefer-on-push-component-change-detection': 'error' },
  },
  {
    files: ['src/**/*.html'],
    extends: [...angular.configs.templateRecommended, ...angular.configs.templateAccessibility],
  },
  {
    files: ['src/app/core/**/*.ts'],
    rules: {
      'no-restricted-imports': [
        'error',
        {
          patterns: [
            {
              group: ['**/features/**', '**/layout/**', '**/shared/**'],
              message: 'Core services cannot depend on features or presentation.',
            },
          ],
        },
      ],
    },
  },
  {
    files: ['src/app/shared/**/*.ts'],
    rules: {
      'no-restricted-imports': [
        'error',
        {
          patterns: [
            {
              group: ['**/features/**', '**/layout/**'],
              message: 'Shared UI cannot depend on features or layout.',
            },
          ],
        },
      ],
    },
  },
);
